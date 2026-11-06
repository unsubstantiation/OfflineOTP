package com.fairuniversal.offlineotp.Utilities

import org.apache.commons.codec.binary.Base32
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.VaultJson
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Portable credential interchange with LinSCP (`https://github.com/unsubstantiation/LinSCP`).
 *
 * Supports:
 * 1. **LinSCP v2 encrypted credential-vault interchange (`LSCPVLT2`)**:
 *    `[8-byte ASCII "LSCPVLT2"][16-byte Argon2id salt][12-byte GCM nonce][AES-256-GCM ct + 16-byte tag]`
 *    keyed by Argon2id v1.3 (`65,536 KiB` memory, `3` iterations, `1` lane, 256-bit key) with
 *    `"LSCPVLT2"` authenticated as GCM associated data, carrying both `SSH_KEY` and `PASSWORD`
 *    entries (with `linscp_session_json`, `linscp_ssh_key_label`, `linscp_tunnel_key_label`).
 *    Supports both importing from and exporting back to LinSCP (`Options → Credential vault`).
 * 2. **LinSCP portable `BackupBundle` JSON (`version = 1`)**:
 *    Decrypts `lscp1$` saved-session passwords using `sessions_key_b64` (or an explicit 32-byte
 *    `sessions.key`) and imports saved SSH/SFTP/SCP/FTP/WebDAV/S3/remote-desktop sessions.
 * 3. **LinSCP `sessions.ini`**:
 *    Parses `[SiteName]` sections directly (plaintext or decrypted with an accompanying 32-byte
 *    `sessions.key`).
 */
object LinScpCredentials {
    const val FILE_MAGIC_TEXT = "LSCPVLT2"
    private val FILE_MAGIC = FILE_MAGIC_TEXT.toByteArray(StandardCharsets.US_ASCII)

    const val ARGON2_MEMORY_KIB = 64 * 1024
    const val ARGON2_ITERATIONS = 3
    const val ARGON2_PARALLELISM = 1
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val GCM_TAG_BYTES = 16
    const val MIN_VAULT_BYTES = 8 + SALT_BYTES + NONCE_BYTES + GCM_TAG_BYTES
    const val MAX_FILE_BYTES = BoundedInputReader.MAX_DOCUMENT_BYTES

    const val SESSION_JSON_COLUMN = "linscp_session_json"
    const val SSH_KEY_LABEL_COLUMN = "linscp_ssh_key_label"
    const val TUNNEL_KEY_LABEL_COLUMN = "linscp_tunnel_key_label"

    const val LSCP1_PREFIX = "lscp1$"
    const val MASTER_KEY_BYTES = 32
    private const val LSCP1_NONCE_BYTES = 16
    private const val LSCP1_TAG_BYTES = 32
    private const val LSCP1_MAX_PASSWORD_BYTES = 4096
    private const val PASSWORD_FIELD_BYTE_LIMIT = 64 * 1024

    private val LSCP1_TAG_LABEL = "lscp1".toByteArray(StandardCharsets.US_ASCII)
    private val LSCP1_ENC_KEY_LABEL =
        "LinSCP sessions file v1 encryption key".toByteArray(StandardCharsets.US_ASCII)
    private val LSCP1_MAC_KEY_LABEL =
        "LinSCP sessions file v1 authentication key".toByteArray(StandardCharsets.US_ASCII)

    private val SENSITIVE_EXTRA_KEYS = setOf(
        "s3_secret_key",
        "s3_session_token",
        "password",
        "proxy_password",
        "tunnel_password",
        "tunnel_passphrase",
        "tls_key_passphrase"
    )
    private val LOCAL_PATH_EXTRA_KEYS = setOf(
        "tls_certificate_file",
        "tls_private_key_file",
        "tls_ca_file",
        "private_key",
        "tunnel_private_key",
        "local_directory"
    )

    /** True when [bytes] starts with the 8-byte `"LSCPVLT2"` encrypted credential-vault marker. */
    @JvmStatic
    fun isEncryptedVault(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < FILE_MAGIC.size) return false
        for (index in FILE_MAGIC.indices) {
            if (bytes[index] != FILE_MAGIC[index]) return false
        }
        return true
    }

    /**
     * True when [bytes] is a plaintext `sessions.ini` (or a `BackupBundle` JSON missing
     * `sessions_key_b64`) that contains `lscp1$` encrypted passwords requiring `sessions.key`.
     */
    @JvmStatic
    fun needsSessionMasterKey(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_FILE_BYTES || isEncryptedVault(bytes)) {
            return false
        }
        val text = decodeUtf8OrNull(bytes) ?: return false
        val trimmed = text.trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("{")) {
            return try {
                InputPolicy.jsonDepth(trimmed)
                val bundle = JSONObject(trimmed)
                val ini = bundle.optString("sessions_ini").orEmpty()
                val keyB64 = nonNullString(bundle, "sessions_key_b64")
                keyB64.isBlank() && iniContainsLscp1Secret(ini)
            } catch (_: Exception) {
                false
            }
        }
        return looksLikeSessionsIni(trimmed) && iniContainsLscp1Secret(trimmed)
    }

    /**
     * Imports SSH keys and saved connections from any supported LinSCP export:
     * - `LSCPVLT2` encrypted credential vault (requires [password]);
     * - `BackupBundle` JSON (`version = 1`, `sessions_ini`, optional `sessions_key_b64`);
     * - `sessions.ini` (with optional 32-byte raw/Base64 [masterKeyBytes] for `lscp1$` secrets).
     */
    @JvmStatic
    @JvmOverloads
    @Throws(GeneralSecurityException::class)
    fun importBytes(
        bytes: ByteArray?,
        password: String? = null,
        masterKeyBytes: ByteArray? = null
    ): ArrayList<Entry> {
        require(bytes != null && bytes.isNotEmpty()) { "LinSCP import file is empty" }
        require(bytes.size <= MAX_FILE_BYTES) { "LinSCP import exceeds 16 MiB" }
        if (isEncryptedVault(bytes)) {
            val requiredPassword = password ?: throw IllegalArgumentException("LinSCP backup password required")
            return openVault(bytes, requiredPassword)
        }
        val text = decodeUtf8Strict(bytes, "LinSCP import file is not valid UTF-8")
            .trimStart('\uFEFF')
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "LinSCP import file is empty" }
        val callerKey = if (masterKeyBytes != null && masterKeyBytes.isNotEmpty()) {
            parseMasterKey(masterKeyBytes)
        } else null
        try {
            if (trimmed.startsWith("{")) {
                return importBackupBundleJson(trimmed, callerKey)
            }
            require(looksLikeSessionsIni(trimmed)) { "Unsupported LinSCP file format" }
            return importSessionsIni(trimmed, callerKey)
        } finally {
            callerKey?.fill(0)
        }
    }

    /**
     * Opens a LinSCP `LSCPVLT2` encrypted credential vault and returns its serialized
     * [VaultJson] string (used so [EncryptedBackupCodec.open] can also restore LinSCP `.json.aes`
     * exports seamlessly).
     */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun openVaultToJson(bytes: ByteArray, password: String): String {
        val entries = openVault(bytes, password)
        return try {
            VaultJson.entriesToString(entries)
        } finally {
            entries.forEach { it.destroy() }
        }
    }

    /**
     * Decrypts and validates a LinSCP `LSCPVLT2` encrypted credential vault, enriching paired
     * `SSH_KEY` entries with their session/jump-host login password and connection host so both
     * the SSH Keys tab and the Passwords tab carry complete, usable credentials.
     */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun openVault(bytes: ByteArray, password: String): ArrayList<Entry> {
        require(bytes.size >= MIN_VAULT_BYTES) { "The LinSCP credential vault is truncated" }
        require(bytes.size <= MAX_FILE_BYTES) { "The LinSCP credential vault exceeds 16 MiB" }
        require(isEncryptedVault(bytes)) { "Unsupported LinSCP credential-vault format or version" }
        require(password.isNotEmpty() && password.length <= InputPolicy.MAX_TEXT_CHARS) {
            "Backup password must contain 1 to 1024 characters"
        }

        val salt = bytes.copyOfRange(FILE_MAGIC.size, FILE_MAGIC.size + SALT_BYTES)
        val nonceStart = FILE_MAGIC.size + SALT_BYTES
        val ciphertextStart = nonceStart + NONCE_BYTES
        val nonce = bytes.copyOfRange(nonceStart, ciphertextStart)
        val ciphertext = bytes.copyOfRange(ciphertextStart, bytes.size)

        val keyBytes = Argon2id.deriveFromPassword(
            password = password,
            salt = salt,
            memoryKiB = ARGON2_MEMORY_KIB,
            iterations = ARGON2_ITERATIONS,
            parallelism = ARGON2_PARALLELISM,
            tagLength = Constants.ARGON2_HASH_LENGTH
        )
        var plaintext: ByteArray? = null
        try {
            val secretKey = SecretKeySpec(keyBytes, "AES")
            plaintext = CryptoPrimitives.decryptWithAad(secretKey, nonce, ciphertext, FILE_MAGIC)
            val jsonText = decodeUtf8Strict(plaintext, "Invalid LinSCP credential-vault UTF-8")
            InputPolicy.jsonDepth(jsonText)
            val array = try {
                if (!jsonText.trimStart().startsWith("[")) {
                    throw IllegalArgumentException("Credential vault must contain a JSON array")
                }
                JSONArray(jsonText)
            } catch (error: JSONException) {
                throw IllegalArgumentException("Invalid credential-vault JSON", error)
            }
            require(array.length() in 1..InputPolicy.MAX_ENTRIES) {
                "Credential vault must contain 1 to ${InputPolicy.MAX_ENTRIES} entries"
            }
            return parseVaultEntries(array)
        } finally {
            keyBytes.fill(0)
            plaintext?.fill(0)
        }
    }

    /**
     * Encrypts all `SSH_KEY` and `PASSWORD` entries from [entries] in LinSCP's `LSCPVLT2`
     * Argon2id + AES-256-GCM interchange format, ready for `Options → Credential vault → Import`
     * in LinSCP.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(GeneralSecurityException::class)
    fun exportVault(
        entries: List<Entry>,
        password: String,
        salt: ByteArray = CryptoPrimitives.generateRandom(SALT_BYTES),
        nonce: ByteArray = CryptoPrimitives.generateRandom(NONCE_BYTES)
    ): ByteArray {
        require(password.isNotEmpty() && password.length <= InputPolicy.MAX_TEXT_CHARS) {
            "Backup password must contain 1 to 1024 characters"
        }
        require(salt.size == SALT_BYTES) { "LinSCP salt must be $SALT_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "LinSCP nonce must be $NONCE_BYTES bytes" }

        val credentials = entries.filter { it.isSshKey() || it.isPassword() }
        require(credentials.isNotEmpty()) { "There are no SSH keys or login credentials to export" }
        require(credentials.size <= InputPolicy.MAX_ENTRIES) {
            "The vault exceeds OfflineOTP's ${InputPolicy.MAX_ENTRIES}-entry limit"
        }

        val linkedKeyLabels = HashSet<String>()
        for (entry in credentials) {
            if (entry.isPassword()) {
                val fields = entry.getPasswordFields()
                val mainKey = fields.value(SSH_KEY_LABEL_COLUMN).trim()
                val tunnelKey = fields.value(TUNNEL_KEY_LABEL_COLUMN).trim()
                if (mainKey.isNotEmpty()) linkedKeyLabels.add(mainKey)
                if (tunnelKey.isNotEmpty()) linkedKeyLabels.add(tunnelKey)
            }
        }

        val encodedArray = JSONArray()
        val usedKeyLabels = HashSet<String>()
        for (entry in credentials) {
            if (entry.isSshKey()) {
                encodedArray.put(encodeSshEntryForLinScp(entry, linkedKeyLabels, usedKeyLabels))
            } else if (entry.isPassword()) {
                encodedArray.put(encodePasswordEntryForLinScp(entry))
            }
        }

        val plainBytes = encodedArray.toString().toByteArray(StandardCharsets.UTF_8)
        val keyBytes = Argon2id.deriveFromPassword(
            password = password,
            salt = salt,
            memoryKiB = ARGON2_MEMORY_KIB,
            iterations = ARGON2_ITERATIONS,
            parallelism = ARGON2_PARALLELISM,
            tagLength = Constants.ARGON2_HASH_LENGTH
        )
        try {
            require(plainBytes.size <= MAX_FILE_BYTES) { "The credential vault exceeds 16 MiB" }
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ciphertext = CryptoPrimitives.encryptWithAad(secretKey, nonce, plainBytes, FILE_MAGIC)
            val total = FILE_MAGIC.size.toLong() + SALT_BYTES + NONCE_BYTES + ciphertext.size
            require(total <= MAX_FILE_BYTES) { "The encrypted vault exceeds 16 MiB" }
            return ByteBuffer.allocate(total.toInt())
                .put(FILE_MAGIC)
                .put(salt)
                .put(nonce)
                .put(ciphertext)
                .array()
        } finally {
            plainBytes.fill(0)
            keyBytes.fill(0)
        }
    }

    /** UTC timestamped filename matching LinSCP's `offlineotp_vault::timestamped_filename`. */
    @JvmStatic
    @JvmOverloads
    fun exportFilename(epochMillis: Long = System.currentTimeMillis()): String {
        val unixSeconds = Math.floorDiv(epochMillis, 1000L).coerceAtLeast(0L)
        val days = Math.floorDiv(unixSeconds, 86_400L)
        val secondsOfDay = Math.floorMod(unixSeconds, 86_400L)
        val (year, month, day) = civilFromDays(days)
        val hour = secondsOfDay / 3_600L
        val minute = (secondsOfDay % 3_600L) / 60L
        val second = secondsOfDay % 60L
        return String.format(
            Locale.ROOT,
            "otp_accounts_%04d-%02d-%02d_%02d-%02d-%02d.json.aes",
            year, month, day, hour, minute, second
        )
    }

    /**
     * Formats a `PASSWORD` entry's fields for the Android/desktop details dialog, expanding
     * `linscp_session_json` into readable connection lines while keeping ordinary columns intact.
     */
    @JvmStatic
    fun formatPasswordDetails(fields: PasswordMaterial.Fields): String {
        val sessionJson = fields.value(SESSION_JSON_COLUMN)
        if (sessionJson.isBlank()) {
            val cols = fields.columns()
            return cols.indices.joinToString("\n\n") { "${cols[it]}: ${fields.valueAt(it)}" }
        }
        val lines = ArrayList<String>()
        fun addLine(label: String, value: String) {
            if (value.isNotEmpty()) lines.add("$label: $value")
        }
        addLine("URL", fields.value("url"))
        addLine("Username", fields.value("username"))
        addLine("Password", fields.value("password"))
        try {
            val site = JSONObject(sessionJson)
            addLine("Protocol", nonNullString(site, "protocol"))
            val host = nonNullString(site, "host")
            val port = site.optInt("port", 0)
            if (host.isNotEmpty()) {
                addLine("Host", if (port > 0) "$host:$port" else host)
            }
            addLine("Remote directory", nonNullString(site, "remote_directory"))
            addLine("Host key", nonNullString(site, "hostkey"))
            addLine("Preset", nonNullString(site, "preset"))
            val sshKeyLabel = fields.value(SSH_KEY_LABEL_COLUMN)
            addLine("SSH key entry", sshKeyLabel)
            val tunnelObj = site.opt("tunnel") as? JSONObject
            if (tunnelObj != null && (tunnelObj.optBoolean("enabled", false) ||
                        nonNullString(tunnelObj, "host").isNotEmpty())) {
                val tHost = nonNullString(tunnelObj, "host")
                val tPort = tunnelObj.optInt("port", 22)
                val tUser = nonNullString(tunnelObj, "username")
                val target = if (tPort > 0) "$tHost:$tPort" else tHost
                addLine("Jump host", if (tUser.isNotEmpty()) "$tUser@$target" else target)
                addLine("Jump-host password", nonNullString(tunnelObj, "password"))
                addLine("Jump-host key entry", fields.value(TUNNEL_KEY_LABEL_COLUMN))
            }
            val proxyMethod = nonNullString(site, "proxy_method")
            val proxyHost = nonNullString(site, "proxy_host")
            if (proxyHost.isNotEmpty() && !proxyMethod.equals("none", ignoreCase = true) && proxyMethod != "0") {
                val proxyPort = site.optInt("proxy_port", 0)
                addLine("Proxy", "$proxyMethod ${if (proxyPort > 0) "$proxyHost:$proxyPort" else proxyHost}".trim())
                addLine("Proxy username", nonNullString(site, "proxy_username"))
                addLine("Proxy password", nonNullString(site, "proxy_password"))
            }
        } catch (_: JSONException) {
            addLine(SESSION_JSON_COLUMN, sessionJson)
        }
        for (col in fields.columns()) {
            val normalized = col.trim().lowercase(Locale.ROOT)
            if (normalized !in setOf(
                    "url", "username", "password",
                    SESSION_JSON_COLUMN, SSH_KEY_LABEL_COLUMN, TUNNEL_KEY_LABEL_COLUMN
                )) {
                addLine(col, fields.value(col))
            }
        }
        return lines.joinToString("\n\n")
    }

    /**
     * Parses a 32-byte LinSCP `sessions.key` (raw 32 bytes or standard Base64 with optional
     * surrounding ASCII whitespace).
     */
    @JvmStatic
    fun parseMasterKey(content: ByteArray): ByteArray {
        if (content.size == MASTER_KEY_BYTES) return content.copyOf()
        var start = 0
        var end = content.size
        while (start < end && isAsciiWhitespace(content[start])) start++
        while (end > start && isAsciiWhitespace(content[end - 1])) end--
        val trimmedLen = end - start
        if (trimmedLen == MASTER_KEY_BYTES) {
            return content.copyOfRange(start, end)
        }
        val ascii = String(content, start, trimmedLen, StandardCharsets.US_ASCII)
        val decoded = try {
            Base64.getDecoder().decode(ascii)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed LinSCP sessions.key", error)
        }
        if (decoded.size != MASTER_KEY_BYTES) {
            decoded.fill(0)
            throw IllegalArgumentException("LinSCP sessions.key must decode to 32 bytes")
        }
        return decoded
    }

    /**
     * Decrypts and authenticates a LinSCP `lscp1$` saved-session password token using the 32-byte
     * `sessions.key` master key.
     */
    @JvmStatic
    fun decryptLscp1Password(encoded: String, masterKey: ByteArray): String {
        require(masterKey.size == MASTER_KEY_BYTES) { "The LinSCP encryption key must be 32 bytes" }
        require(encoded.startsWith(LSCP1_PREFIX)) { "Unknown LinSCP password format" }
        val b64 = encoded.substring(LSCP1_PREFIX.length)
        val blob = try {
            Base64.getDecoder().decode(b64)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Corrupted lscp1 ciphertext", error)
        }
        require(blob.size >= LSCP1_NONCE_BYTES + LSCP1_TAG_BYTES &&
                blob.size - LSCP1_NONCE_BYTES - LSCP1_TAG_BYTES <= LSCP1_MAX_PASSWORD_BYTES) {
            "Corrupted lscp1 ciphertext length"
        }

        val nonce = blob.copyOfRange(0, LSCP1_NONCE_BYTES)
        val cipher = blob.copyOfRange(LSCP1_NONCE_BYTES, blob.size - LSCP1_TAG_BYTES)
        val tag = blob.copyOfRange(blob.size - LSCP1_TAG_BYTES, blob.size)

        val encKey = hmacSha256(masterKey, LSCP1_ENC_KEY_LABEL)
        val macKey = hmacSha256(masterKey, LSCP1_MAC_KEY_LABEL)
        var plainBytes: ByteArray? = null
        try {
            val expectedTag = hmacSha256(macKey, LSCP1_TAG_LABEL, nonce, cipher)
            val tagValid = MessageDigest.isEqual(expectedTag, tag)
            expectedTag.fill(0)
            require(tagValid) { "LinSCP session password authentication failed" }

            plainBytes = xorKeystream(encKey, nonce, cipher)
            return decodeUtf8Strict(plainBytes, "Decrypted LinSCP password is not valid UTF-8")
        } finally {
            encKey.fill(0)
            macKey.fill(0)
            plainBytes?.fill(0)
        }
    }

    // --- Internal decoders & encoders ---

    private data class KeyLinkInfo(
        val host: String,
        val port: Int,
        val username: String,
        val password: String
    )

    private fun parseVaultEntries(array: JSONArray): ArrayList<Entry> {
        val rawSshRows = ArrayList<JSONObject>()
        val rawLoginRows = ArrayList<JSONObject>()
        val orderedKinds = ArrayList<Pair<String, Int>>()

        for (index in 0 until array.length()) {
            val item = array.opt(index) as? JSONObject
                ?: throw IllegalArgumentException("Entry ${index + 1} is not a JSON object")
            val kind = nonNullString(item, "type")
            when (kind) {
                "SSH_KEY" -> {
                    orderedKinds.add("SSH_KEY" to rawSshRows.size)
                    rawSshRows.add(item)
                }
                "PASSWORD" -> {
                    orderedKinds.add("PASSWORD" to rawLoginRows.size)
                    rawLoginRows.add(item)
                }
                else -> {
                    // Non-SSH/login OfflineOTP entries in a mixed vault are ignored, matching LinSCP.
                }
            }
        }

        val keyLinks = HashMap<String, KeyLinkInfo>()
        val decodedLogins = ArrayList<Entry>(rawLoginRows.size)
        val decodedKeys = ArrayList<Entry>(rawSshRows.size)
        try {
            for ((idx, row) in rawLoginRows.withIndex()) {
                val (entry, links) = decodeLoginRow(row, idx)
                decodedLogins.add(entry)
                for ((keyLabel, info) in links) {
                    keyLinks.putIfAbsent(keyLabel, info)
                }
            }
            for ((idx, row) in rawSshRows.withIndex()) {
                decodedKeys.add(decodeSshRow(row, idx, keyLinks))
            }

            val result = ArrayList<Entry>(decodedKeys.size + decodedLogins.size)
            for ((kind, subIndex) in orderedKinds) {
                if (kind == "SSH_KEY") result.add(decodedKeys[subIndex])
                else result.add(decodedLogins[subIndex])
            }
            require(result.isNotEmpty()) { "The LinSCP vault contains no SSH keys or login entries" }
            return result
        } catch (error: RuntimeException) {
            decodedLogins.forEach { it.destroy() }
            decodedKeys.forEach { it.destroy() }
            throw error
        }
    }

    private fun decodeSshRow(
        row: JSONObject,
        index: Int,
        keyLinks: Map<String, KeyLinkInfo>
    ): Entry {
        val label = requiredText(row, "label", index)
        validateLabel(label, index)
        val secretBytes = decodeCanonicalBase32(row, index, SshKeyMaterial.MAX_ENCODED_BYTES)
        val keys = try {
            SshKeyMaterial.decode(secretBytes)
        } finally {
            secretBytes.fill(0)
        }
        validateKeyText(keys.publicKey, "Public key")
        validateKeyText(keys.privateKey, "Private key")

        var host = optionalText(row, "ssh_host", index).orEmpty()
        var port = optionalPort(row, "ssh_port", index)
        var username = optionalText(row, "ssh_username", index).orEmpty()
        var password = optionalText(row, "ssh_password", index).orEmpty()

        val linked = keyLinks[label]
        if (linked != null) {
            if (host.isEmpty() && isValidSshToken(linked.host, SshConnection.MAX_HOST_CHARS)) {
                host = linked.host
            }
            if (port == 0 && linked.port in SshConnection.MIN_PORT..SshConnection.MAX_PORT) {
                port = linked.port
            }
            if (username.isEmpty() && isValidSshToken(linked.username, SshConnection.MAX_USERNAME_CHARS)) {
                username = linked.username
            }
            if (password.isEmpty() && isValidSshPassword(linked.password)) {
                password = linked.password
            }
        }

        val connection = SshConnection.of(host, port, username, password)
        val storedIssuer = optionalText(row, "issuer", index).orEmpty()
        val issuer = storedIssuer.ifBlank { connection?.host.orEmpty() }
        val tags = optionalTags(row, index)
        return Entry.sshKey(keys.publicKey, keys.privateKey, issuer, label, tags, connection)
    }

    private fun decodeLoginRow(
        row: JSONObject,
        index: Int
    ): Pair<Entry, Map<String, KeyLinkInfo>> {
        val label = requiredText(row, "label", index)
        validateLabel(label, index)
        val secretBytes = decodeCanonicalBase32(row, index, PasswordMaterial.MAX_BYTES)
        val fields = try {
            PasswordMaterial.decode(secretBytes)
        } finally {
            secretBytes.fill(0)
        }

        val columns = fields.columns()
        val orderedPairs = ArrayList<Pair<String, String>>(columns.size)
        val byLower = LinkedHashMap<String, String>()
        for (i in columns.indices) {
            val name = columns[i]
            val value = fields.valueAt(i)
            validatePasswordText(name, "Login field name", index)
            validatePasswordText(value, "Login field value", index)
            val norm = name.trim().lowercase(Locale.ROOT)
            require(byLower.put(norm, value) == null) { "Entry ${index + 1} has duplicate login field names" }
            orderedPairs.add(name to value)
        }

        val url = byLower["url"] ?: throw IllegalArgumentException("Entry ${index + 1} has no URL field")
        val username = byLower["username"] ?: throw IllegalArgumentException("Entry ${index + 1} has no username field")
        val password = byLower["password"] ?: throw IllegalArgumentException("Entry ${index + 1} has no password field")

        val sessionJson = byLower[SESSION_JSON_COLUMN].orEmpty()
        var sessionHost = ""
        var sessionPort = 0
        var sessionUser = username
        var tunnelHost = ""
        var tunnelPort = 0
        var tunnelUser = ""
        var tunnelPassword = ""
        if (sessionJson.isNotEmpty()) {
            InputPolicy.jsonDepth(sessionJson)
            val site = try {
                JSONObject(sessionJson)
            } catch (error: JSONException) {
                throw IllegalArgumentException("Entry ${index + 1} has invalid linscp_session_json", error)
            }
            sessionHost = nonNullString(site, "host")
            sessionPort = site.optInt("port", 0).takeIf { it in 1..65535 } ?: 0
            if (sessionUser.isEmpty()) sessionUser = nonNullString(site, "username")
            val tunnel = site.opt("tunnel") as? JSONObject
            if (tunnel != null) {
                tunnelHost = nonNullString(tunnel, "host")
                tunnelPort = tunnel.optInt("port", 0).takeIf { it in 1..65535 } ?: 0
                tunnelUser = nonNullString(tunnel, "username")
                tunnelPassword = nonNullString(tunnel, "password")
            }
        }

        val links = LinkedHashMap<String, KeyLinkInfo>()
        val mainKeyLabel = byLower[SSH_KEY_LABEL_COLUMN].orEmpty()
        if (mainKeyLabel.isNotEmpty()) {
            links[mainKeyLabel] = KeyLinkInfo(sessionHost, sessionPort, sessionUser, password)
        }
        val tunnelKeyLabel = byLower[TUNNEL_KEY_LABEL_COLUMN].orEmpty()
        if (tunnelKeyLabel.isNotEmpty()) {
            links[tunnelKeyLabel] = KeyLinkInfo(tunnelHost, tunnelPort, tunnelUser, tunnelPassword)
        }

        val storedIssuer = optionalText(row, "issuer", index).orEmpty()
        val issuer = storedIssuer.ifBlank { sessionHost }.ifBlank { hostFromUrl(url) }
        val tags = optionalTags(row, index)
        val entry = Entry.passwordFields(label, issuer, orderedPairs, tags)
        return entry to links
    }

    private fun encodeSshEntryForLinScp(
        entry: Entry,
        linkedKeyLabels: Set<String>,
        usedKeyLabels: MutableSet<String>
    ): JSONObject {
        val keys = entry.getSshKeys()
        validateKeyText(keys.publicKey, "Public key")
        validateKeyText(keys.privateKey, "Private key")
        val rawMaterial = SshKeyMaterial.encode(keys.publicKey, keys.privateKey)
        val secretBase32 = try {
            String(Base32().encode(rawMaterial), StandardCharsets.US_ASCII)
        } finally {
            rawMaterial.fill(0)
        }

        val rawLabel = entry.getLabel().trim().ifEmpty {
            entry.getIssuer().trim().ifEmpty { "SSH key" }
        }
        val baseLabel = if (rawLabel in linkedKeyLabels) {
            rawLabel
        } else {
            sanitizeLinScpSessionName(rawLabel)
        }
        val uniqueLabel = deduplicateLabel(baseLabel, usedKeyLabels)
        validateLabel(uniqueLabel, 0)

        val obj = baseEntryJson("SSH_KEY", entry.getIssuer(), uniqueLabel, secretBase32, entry.getTags())
        val conn = entry.getSshConnection()
        if (conn != null) {
            if (conn.host.isNotEmpty()) obj.put("ssh_host", conn.host)
            if (conn.port in SshConnection.MIN_PORT..SshConnection.MAX_PORT) obj.put("ssh_port", conn.port)
            if (conn.username.isNotEmpty()) obj.put("ssh_username", conn.username)
            if (conn.password.isNotEmpty()) obj.put("ssh_password", conn.password)
        }
        return obj
    }

    private fun encodePasswordEntryForLinScp(entry: Entry): JSONObject {
        val fields = entry.getPasswordFields()
        val url = fields.value("url")
        val username = fields.value("username")
        val password = fields.value("password")
        validatePasswordText(url, "URL", 0)
        validatePasswordText(username, "Username", 0)
        validatePasswordText(password, "Password", 0)

        val exportPairs = ArrayList<Pair<String, String>>()
        exportPairs.add("url" to url)
        exportPairs.add("username" to username)
        exportPairs.add("password" to password)

        val extraLinScp = fields.columns()
            .map { it.trim() }
            .filter { it.lowercase(Locale.ROOT).startsWith("linscp_") }
            .sortedBy { it.lowercase(Locale.ROOT) }
        for (col in extraLinScp) {
            val norm = col.lowercase(Locale.ROOT)
            val value = fields.value(col)
            validatePasswordText(norm, "Metadata column", 0)
            validatePasswordText(value, "Metadata value", 0)
            exportPairs.add(norm to value)
        }

        val rawMaterial = PasswordMaterial.encodeFields(exportPairs)
        val secretBase32 = try {
            String(Base32().encode(rawMaterial), StandardCharsets.US_ASCII)
        } finally {
            rawMaterial.fill(0)
        }

        val rawLabel = entry.getLabel().trim().ifEmpty {
            entry.getIssuer().trim().ifEmpty { "Saved session" }
        }
        val cleanLabel = sanitizeLinScpSessionName(rawLabel)
        validateLabel(cleanLabel, 0)
        return baseEntryJson("PASSWORD", entry.getIssuer(), cleanLabel, secretBase32, entry.getTags())
    }

    private fun baseEntryJson(
        kind: String,
        issuer: String,
        label: String,
        secretBase32: String,
        tags: List<String>
    ): JSONObject {
        val tagsArray = JSONArray()
        for (tag in tags) tagsArray.put(tag)
        return JSONObject()
            .put("secret", secretBase32)
            .put("issuer", issuer)
            .put("label", label)
            .put("digits", 6)
            .put("type", kind)
            .put("algorithm", "SHA1")
            .put("thumbnail", "")
            .put("last_used", 0)
            .put("used_frequency", 0)
            .put("tags", tagsArray)
    }

    // --- BackupBundle JSON & sessions.ini parsing ---

    private fun importBackupBundleJson(jsonText: String, callerMasterKey: ByteArray?): ArrayList<Entry> {
        InputPolicy.jsonDepth(jsonText)
        val bundle = try {
            JSONObject(jsonText)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid LinSCP backup JSON", error)
        }
        if (bundle.has("version")) {
            val version = bundle.optInt("version", -1)
            require(version == 1) { "Unsupported LinSCP backup bundle version: $version" }
        }
        require(bundle.has("sessions_ini")) { "JSON is not a LinSCP BackupBundle" }
        val iniText = nonNullString(bundle, "sessions_ini")
        require(iniText.isNotBlank()) { "LinSCP backup contains no saved sessions" }

        val keyB64 = nonNullString(bundle, "sessions_key_b64").trim()
        val bundleKey = if (keyB64.isNotEmpty()) {
            parseMasterKey(keyB64.toByteArray(StandardCharsets.US_ASCII))
        } else {
            callerMasterKey?.copyOf()
        }
        return try {
            importSessionsIni(iniText, bundleKey)
        } finally {
            bundleKey?.fill(0)
        }
    }

    private fun importSessionsIni(iniText: String, masterKey: ByteArray?): ArrayList<Entry> {
        val sites = parseSessionsIni(iniText, masterKey)
        require(sites.isNotEmpty()) { "No saved sessions found in LinSCP configuration" }
        require(sites.size <= InputPolicy.MAX_ENTRIES) {
            "LinSCP import exceeds ${InputPolicy.MAX_ENTRIES} sessions"
        }

        val entries = ArrayList<Entry>(sites.size)
        try {
            var totalBytes = 0L
            for (site in sites) {
                validateSessionForExport(site)
                val exportedPassword = site.password?.takeIf { it.isNotEmpty() }
                    ?: site.extraFields["s3_secret_key"].orEmpty()
                val exportedUsername = site.username.ifEmpty {
                    site.extraFields["s3_access_key"].orEmpty()
                }
                val sessionJson = buildPortableSessionJson(site, exportedUsername, exportedPassword)
                val url = buildSessionUrl(site.protocol, site.host, site.port)
                val fields = listOf(
                    "url" to url,
                    "username" to exportedUsername,
                    "password" to exportedPassword,
                    SESSION_JSON_COLUMN to sessionJson
                )
                val issuer = site.host.ifBlank { "LinSCP" }
                val entry = Entry.passwordFields(site.name, issuer, fields)
                entries.add(entry)
                totalBytes += entry.getSecretSize()
                require(totalBytes <= PasswordImport.MAX_BATCH_PAYLOAD_BYTES) {
                    "LinSCP import exceeds vault batch size limit"
                }
            }
            return entries
        } catch (error: RuntimeException) {
            entries.forEach { it.destroy() }
            throw error
        }
    }

    private class ParsedIniSite(
        val name: String,
        val protocol: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String?,
        val hostkey: String?,
        val remoteDirectory: String,
        val preset: String,
        val lastUsed: Long?,
        val proxyMethod: String,
        val proxyHost: String,
        val proxyPort: Int,
        val proxyUsername: String,
        val proxyPassword: String?,
        val proxyCommand: String,
        val proxyExclude: String,
        val proxyDns: String,
        val proxyLocalhost: Boolean,
        val tunnelEnabled: Boolean,
        val tunnelHost: String,
        val tunnelPort: Int,
        val tunnelUsername: String,
        val tunnelPassword: String?,
        val tunnelPassphrase: String?,
        val tunnelHostkey: String,
        val tunnelLocalPort: Int,
        val extraFields: Map<String, String>
    )

    private fun parseSessionsIni(iniText: String, masterKey: ByteArray?): List<ParsedIniSite> {
        val byName = LinkedHashMap<String, ParsedIniSite>()
        var currentSection: String? = null
        val currentFields = LinkedHashMap<String, String>()

        fun flushSection() {
            val sectionName = currentSection?.trim()?.takeIf { it.isNotEmpty() }
            if (sectionName == null) {
                currentFields.clear()
                return
            }
            val map = LinkedHashMap(currentFields)
            currentFields.clear()

            val protocol = normalizeProtocol(map.remove("protocol"))
            val host = map.remove("host").orEmpty()
            val port = map.remove("port")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: defaultPortForProtocol(protocol)
            val username = map.remove("username").orEmpty()
            val password = decodeIniSecret(map.remove("password"), masterKey, sectionName, "password")
            map.remove("private_key")
            val hostkey = map.remove("hostkey")?.takeIf { it.isNotEmpty() }
            val remoteDirectory = map.remove("remote_directory").orEmpty()
            map.remove("local_directory")
            val preset = map.remove("preset").orEmpty()
            val lastUsed = map.remove("last_used")?.trim()?.toLongOrNull()?.takeIf { it >= 0L }

            val proxyMethod = map.remove("proxy_method").orEmpty()
            val proxyHost = map.remove("proxy_host").orEmpty()
            val proxyPort = map.remove("proxy_port")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
            val proxyUsername = map.remove("proxy_username").orEmpty()
            val proxyPassword = decodeIniSecret(map.remove("proxy_password"), masterKey, sectionName, "proxy_password")
            val proxyCommand = map.remove("proxy_command").orEmpty()
            val proxyExclude = map.remove("proxy_exclude").orEmpty()
            val proxyDns = map.remove("proxy_dns").orEmpty()
            val proxyLocalhost = parseToggleIni(map.remove("proxy_localhost")) ?: false

            val tunnelEnabled = parseToggleIni(map.remove("tunnel")) ?: false
            val tunnelHost = map.remove("tunnel_host").orEmpty()
            val tunnelPort = map.remove("tunnel_port")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
            val tunnelUsername = map.remove("tunnel_username").orEmpty()
            val tunnelPassword = decodeIniSecret(map.remove("tunnel_password"), masterKey, sectionName, "tunnel_password")
            map.remove("tunnel_private_key")
            val tunnelPassphrase = decodeIniSecret(map.remove("tunnel_passphrase"), masterKey, sectionName, "tunnel_passphrase")
            val tunnelHostkey = map.remove("tunnel_hostkey").orEmpty()
            val tunnelLocalPort = map.remove("tunnel_local_port")?.trim()?.toIntOrNull()?.takeIf { it in 0..65535 } ?: 0

            byName[sectionName] = ParsedIniSite(
                name = sectionName,
                protocol = protocol,
                host = host,
                port = port,
                username = username,
                password = password,
                hostkey = hostkey,
                remoteDirectory = remoteDirectory,
                preset = preset,
                lastUsed = lastUsed,
                proxyMethod = proxyMethod,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword,
                proxyCommand = proxyCommand,
                proxyExclude = proxyExclude,
                proxyDns = proxyDns,
                proxyLocalhost = proxyLocalhost,
                tunnelEnabled = tunnelEnabled,
                tunnelHost = tunnelHost,
                tunnelPort = tunnelPort,
                tunnelUsername = tunnelUsername,
                tunnelPassword = tunnelPassword,
                tunnelPassphrase = tunnelPassphrase,
                tunnelHostkey = tunnelHostkey,
                tunnelLocalPort = tunnelLocalPort,
                extraFields = LinkedHashMap(map)
            )
        }

        for (rawLine in iniText.lineSequence()) {
            val line = rawLine.removeSuffix("\r")
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                flushSection()
                currentSection = unescapeIniValue(trimmed.substring(1, trimmed.length - 1))
                continue
            }
            val eq = line.indexOf('=')
            require(eq > 0 && currentSection != null) { "Malformed LinSCP sessions.ini line" }
            val key = line.substring(0, eq).trim()
            val value = unescapeIniValue(line.substring(eq + 1))
            currentFields[key] = value
        }
        flushSection()
        return ArrayList(byName.values)
    }

    private fun decodeIniSecret(
        raw: String?,
        masterKey: ByteArray?,
        sectionName: String,
        fieldName: String
    ): String? {
        if (raw.isNullOrEmpty()) return null
        if (!raw.startsWith(LSCP1_PREFIX)) return raw
        val key = masterKey ?: throw IllegalArgumentException(
            "Session '$sectionName' has an encrypted $fieldName requiring LinSCP's sessions.key"
        )
        return decryptLscp1Password(raw, key)
    }

    private fun validateSessionForExport(site: ParsedIniSite) {
        require(site.name.trim().isNotEmpty() && site.name.length <= InputPolicy.MAX_TEXT_CHARS &&
                !site.name.contains(']') && site.name.none { it.isISOControl() }) {
            "Saved session '${site.name}' has an invalid name"
        }
        require(site.extraFields["s3_session_token"].isNullOrEmpty()) {
            "Session '${site.name}' contains an ephemeral S3 session token"
        }
        for ((key, value) in site.extraFields) {
            val lower = key.lowercase(Locale.ROOT)
            if (value.isNotEmpty() && lower in SENSITIVE_EXTRA_KEYS && lower != "s3_secret_key") {
                throw IllegalArgumentException("Session '${site.name}' contains unsupported credential setting '$key'")
            }
        }
        for (text in listOf(
            site.host, site.username, site.password.orEmpty(), site.hostkey.orEmpty(),
            site.remoteDirectory, site.preset, site.proxyMethod, site.proxyHost,
            site.proxyUsername, site.proxyPassword.orEmpty(), site.proxyCommand,
            site.proxyExclude, site.proxyDns, site.tunnelHost, site.tunnelUsername,
            site.tunnelPassword.orEmpty(), site.tunnelHostkey, site.tunnelPassphrase.orEmpty()
        )) {
            require(text.none { it.isISOControl() }) { "Session '${site.name}' contains control characters" }
        }
    }

    private fun buildPortableSessionJson(
        site: ParsedIniSite,
        exportedUsername: String,
        exportedPassword: String
    ): String {
        val tunnelJson = JSONObject()
            .put("enabled", site.tunnelEnabled)
            .put("host", site.tunnelHost)
            .put("port", site.tunnelPort)
            .put("username", site.tunnelUsername)
            .put("hostkey", site.tunnelHostkey)
            .put("local_port", site.tunnelLocalPort)
        if (!site.tunnelPassword.isNullOrEmpty()) tunnelJson.put("password", site.tunnelPassword)

        val extraJson = JSONObject()
        for ((key, value) in site.extraFields.entries.sortedBy { it.key }) {
            val lower = key.lowercase(Locale.ROOT)
            if (lower == "s3_access_key" || lower in SENSITIVE_EXTRA_KEYS || lower in LOCAL_PATH_EXTRA_KEYS) {
                continue
            }
            if (key.isNotEmpty() && key.length <= 128 &&
                key.all { it.isLetterOrDigit() || it == '_' } &&
                value.none { it.isISOControl() }) {
                extraJson.put(key, value)
            }
        }

        val obj = JSONObject()
            .put("name", site.name)
            .put("protocol", site.protocol)
            .put("host", site.host)
            .put("port", site.port)
            .put("username", exportedUsername)
            .put("password", if (exportedPassword.isNotEmpty()) exportedPassword else JSONObject.NULL)
            .put("password_raw", JSONObject.NULL)
            .put("password_error", JSONObject.NULL)
            .put("private_key", JSONObject.NULL)
            .put("hostkey", site.hostkey ?: JSONObject.NULL)
            .put("remote_directory", site.remoteDirectory)
            .put("local_directory", "")
            .put("preset", site.preset)
            .put("last_used", site.lastUsed ?: JSONObject.NULL)
            .put("proxy_method", site.proxyMethod)
            .put("proxy_host", site.proxyHost)
            .put("proxy_port", site.proxyPort)
            .put("proxy_username", site.proxyUsername)
            .put("proxy_password", site.proxyPassword?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("proxy_password_raw", JSONObject.NULL)
            .put("proxy_command", site.proxyCommand)
            .put("proxy_exclude", site.proxyExclude)
            .put("proxy_dns", site.proxyDns)
            .put("proxy_localhost", site.proxyLocalhost)
            .put("proxy_error", JSONObject.NULL)
            .put("tunnel", tunnelJson)
            .put("tunnel_password_raw", JSONObject.NULL)
            .put("tunnel_passphrase", site.tunnelPassphrase?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("tunnel_passphrase_raw", JSONObject.NULL)
            .put("tunnel_error", JSONObject.NULL)
            .put("setting_error", JSONObject.NULL)
            .put("extra_fields", extraJson)
        return obj.toString()
    }

    private fun buildSessionUrl(protocol: String, host: String, port: Int): String {
        if (host.isEmpty()) return "linscp://saved-session"
        val formattedHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
        val effectivePort = if (port == 0) defaultPortForProtocol(protocol) else port
        return "$protocol://$formattedHost:$effectivePort"
    }

    private fun normalizeProtocol(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
        "scp" -> "scp"
        "ssh" -> "ssh"
        "ftp" -> "ftp"
        "ftps" -> "ftps"
        "ftpes" -> "ftpes"
        "dav", "http" -> "dav"
        "davs", "https" -> "davs"
        "webdav" -> "webdav"
        "webdavs" -> "webdavs"
        "s3", "s3s" -> "s3"
        "local" -> "local"
        "rdp", "rdps", "msrdp", "ms-rdp" -> "rdp"
        "vnc", "rfb" -> "vnc"
        "spice" -> "spice"
        "x2go", "nx", "nx3" -> "x2go"
        "x11", "x", "waypipe" -> "x11"
        else -> "sftp"
    }

    private fun defaultPortForProtocol(protocol: String): Int = when (protocol) {
        "sftp", "scp", "ssh", "x2go", "x11" -> 22
        "ftp", "ftpes" -> 21
        "ftps" -> 990
        "dav" -> 80
        "davs", "webdav", "webdavs", "s3" -> 443
        "rdp" -> 3389
        "vnc", "spice" -> 5900
        "local" -> 0
        else -> 22
    }

    private fun unescapeIniValue(escaped: String): String {
        val out = StringBuilder(escaped.length)
        var i = 0
        while (i < escaped.length) {
            val ch = escaped[i++]
            if (ch == '\\' && i < escaped.length) {
                when (val next = escaped[i++]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    '\\' -> out.append('\\')
                    else -> {
                        out.append('\\')
                        out.append(next)
                    }
                }
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }

    private fun parseToggleIni(raw: String?): Boolean? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        null, "" -> null
        "0", "off", "no", "false" -> false
        "1", "2", "on", "yes", "true", "auto", "automatic" -> true
        else -> throw IllegalArgumentException("Invalid toggle value in LinSCP sessions.ini")
    }

    private fun looksLikeSessionsIni(text: String): Boolean {
        var sawSection = false
        for (rawLine in text.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length > 2) {
                sawSection = true
                continue
            }
            if (!sawSection || '=' !in rawLine) return false
        }
        return sawSection
    }

    private fun iniContainsLscp1Secret(iniText: String): Boolean =
        iniText.lineSequence().any { line ->
            val eq = line.indexOf('=')
            eq > 0 && line.substring(eq + 1).trimStart().startsWith(LSCP1_PREFIX)
        }

    // --- Validation & crypto helpers ---

    private fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    private fun xorKeystream(encKey: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var counter = 0
        var offset = 0
        val counterBytes = ByteArray(4)
        while (offset < input.size) {
            ByteBuffer.wrap(counterBytes).putInt(counter)
            val block = hmacSha256(encKey, nonce, counterBytes)
            try {
                val take = minOf(input.size - offset, block.size)
                for (i in 0 until take) {
                    out[offset + i] = (input[offset + i].toInt() xor block[i].toInt()).toByte()
                }
                offset += take
                counter++
            } finally {
                block.fill(0)
            }
        }
        return out
    }

    private fun decodeCanonicalBase32(row: JSONObject, index: Int, maxDecoded: Int): ByteArray {
        val encoded = requiredText(row, "secret", index)
        require(encoded.length <= maxDecoded * 2) { "Entry ${index + 1} secret exceeds its size limit" }
        val normalized = encoded.trim().uppercase(Locale.ROOT)
        val codec = Base32()
        val bytes = codec.decode(normalized)
        val reencoded = String(codec.encode(bytes), StandardCharsets.US_ASCII)
        if (bytes.isEmpty() || bytes.size > maxDecoded || reencoded != normalized) {
            bytes.fill(0)
            throw IllegalArgumentException("Entry ${index + 1} has non-canonical or oversized Base32 secret")
        }
        return bytes
    }

    private fun requiredText(row: JSONObject, field: String, index: Int): String {
        val value = row.opt(field)
        return value as? String ?: throw IllegalArgumentException("Entry ${index + 1} is missing '$field'")
    }

    private fun optionalText(row: JSONObject, field: String, index: Int): String? {
        if (!row.has(field)) return null
        val value = row.opt(field)
        if (value == null || value === JSONObject.NULL) return null
        return value as? String ?: throw IllegalArgumentException("Entry ${index + 1} has invalid '$field'")
    }

    private fun optionalPort(row: JSONObject, field: String, index: Int): Int {
        if (!row.has(field)) return 0
        val value = row.opt(field)
        if (value == null || value === JSONObject.NULL) return 0
        val number = (value as? Number)?.toLong()
            ?: throw IllegalArgumentException("Entry ${index + 1} has invalid '$field'")
        require(number in SshConnection.MIN_PORT.toLong()..SshConnection.MAX_PORT.toLong()) {
            "Entry ${index + 1} has out-of-range '$field'"
        }
        return number.toInt()
    }

    private fun optionalTags(row: JSONObject, index: Int): ArrayList<String> {
        val out = ArrayList<String>()
        if (!row.has("tags")) return out
        val raw = row.opt("tags")
        if (raw == null || raw === JSONObject.NULL) return out
        val arr = raw as? JSONArray ?: throw IllegalArgumentException("Entry ${index + 1} has invalid tags")
        require(arr.length() <= InputPolicy.MAX_TAGS) { "Entry ${index + 1} has too many tags" }
        for (i in 0 until arr.length()) {
            val tag = arr.opt(i) as? String ?: throw IllegalArgumentException("Entry ${index + 1} has non-text tag")
            require(tag.length <= InputPolicy.MAX_TAG_CHARS && tag.none { it.isISOControl() }) {
                "Entry ${index + 1} has invalid tag"
            }
            out.add(tag)
        }
        return out
    }

    private fun validateLabel(label: String, index: Int) {
        require(label.trim().isNotEmpty() && label.length <= InputPolicy.MAX_TEXT_CHARS &&
                label.none { it.isISOControl() }) {
            "Entry ${index + 1} has an invalid label"
        }
    }

    private fun validateKeyText(value: String, label: String) {
        require(value.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            "$label contains unsupported control characters"
        }
    }

    private fun validatePasswordText(value: String, label: String, index: Int) {
        val utf8Len = value.toByteArray(StandardCharsets.UTF_8).size
        require(utf8Len <= PASSWORD_FIELD_BYTE_LIMIT && value.none { it.isISOControl() }) {
            "Entry ${index + 1}: $label exceeds OfflineOTP/LinSCP text limits"
        }
    }

    private fun isValidSshToken(value: String, maxChars: Int): Boolean =
        value.isNotEmpty() && value.length <= maxChars && value.none { it.isWhitespace() || it.isISOControl() }

    private fun isValidSshPassword(value: String): Boolean =
        value.trim().isNotEmpty() && value.length <= SshConnection.MAX_PASSWORD_CHARS &&
                value.none { it.isISOControl() }

    private fun sanitizeLinScpSessionName(raw: String): String =
        raw.replace('[', '(').replace(']', ')').trim().ifEmpty { "Saved session" }

    private fun deduplicateLabel(base: String, seen: MutableSet<String>): String {
        if (seen.add(base)) return base
        var suffix = 2
        while (true) {
            val candidate = "$base ($suffix)"
            if (seen.add(candidate)) return candidate
            suffix++
        }
    }

    private fun hostFromUrl(url: String): String {
        val schemeIdx = url.indexOf("://")
        if (schemeIdx < 0) return ""
        val authority = url.substring(schemeIdx + 3).substringBefore('/')
            .substringAfterLast('@')
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            return if (close > 1) authority.substring(1, close) else ""
        }
        return authority.substringBefore(':')
    }

    private fun nonNullString(obj: JSONObject, key: String): String {
        val raw = obj.opt(key)
        return if (raw == null || raw === JSONObject.NULL) "" else (raw as? String).orEmpty()
    }

    private fun isAsciiWhitespace(b: Byte): Boolean =
        b == ' '.code.toByte() || b == '\n'.code.toByte() ||
                b == '\r'.code.toByte() || b == '\t'.code.toByte()

    private fun decodeUtf8Strict(bytes: ByteArray, errorMessage: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException(errorMessage, error)
    }

    private fun decodeUtf8OrNull(bytes: ByteArray): String? = try {
        decodeUtf8Strict(bytes, "")
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun civilFromDays(daysSinceEpoch: Long): Triple<Long, Long, Long> {
        val z = daysSinceEpoch + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        var year = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val day = doy - (153L * mp + 2L) / 5L + 1L
        val month = mp + if (mp < 10L) 3L else -9L
        if (month <= 2L) year += 1L
        return Triple(year, month, day)
    }
}
