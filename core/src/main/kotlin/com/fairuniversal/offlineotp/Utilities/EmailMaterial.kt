package com.fairuniversal.offlineotp.Utilities

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKey
import javax.crypto.spec.PBEKeySpec
import java.util.zip.GZIPInputStream

/** FairEmail account transport metadata. Stored as JSON inside the encrypted vault entry. */
object EmailMaterial {
    const val MAX_BYTES = 128 * 1024
    const val MAX_CREDENTIAL_CHARS = 16 * 1024
    private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

    data class Account(val account: JSONObject, val identities: List<JSONObject>) {
        val identity: JSONObject get() = identities.first()
        val name: String get() = account.optString("name").orEmpty().ifBlank { identity.optString("email").orEmpty() }
        val address: String get() = identity.optString("email").orEmpty()
        override fun toString(): String = "EmailAccount(redacted)"
    }

    /**
     * One label/value row of an account's connection details. Both clients render these rows, so
     * the desktop dialog and the Android card cannot describe the same stored account differently.
     */
    data class Detail(val label: String, val value: String, val secret: Boolean = false)

    /** Replacement shown instead of a stored password when details are not being revealed. */
    const val SECRET_MASK = "********"

    /**
     * Read-only description of [account]: name and address, then the incoming server, then one
     * group per outgoing identity. Passwords are masked unless [revealSecrets] is true; a blank
     * password stays blank rather than showing a mask for a value that is not stored.
     */
    @JvmStatic
    fun details(account: Account, revealSecrets: Boolean): List<Detail> {
        val rows = ArrayList<Detail>()
        rows.add(Detail("Account", account.account.optString("name").orEmpty()))
        rows.add(Detail("Address", account.address))
        val protocolValue = if (account.account.has("protocol")) account.account.optInt("protocol", -1)
            else if (account.account.optBoolean("pop")) 1 else 0
        val protocol = when (protocolValue) { 0 -> "IMAP"; 1 -> "POP"; else -> "Unknown ($protocolValue)" }
        rows.addAll(connection("Incoming ($protocol)", account.account, revealSecrets))
        account.identities.forEachIndexed { index, identity ->
            val title = "Outgoing (SMTP)" + if (index == 0) "" else " #${index + 1}"
            rows.add(Detail("$title sender name", identity.optString("name").orEmpty()))
            rows.add(Detail("$title sender address", identity.optString("email").orEmpty()))
            rows.add(Detail("$title primary identity", if (identity.optBoolean("primary")) "Yes" else "No"))
            rows.addAll(connection(title, identity, revealSecrets))
        }
        return rows
    }

    private fun connection(title: String, json: JSONObject, revealSecrets: Boolean): List<Detail> {
        val password = json.optString("password").orEmpty()
        val shown = when {
            password.isEmpty() -> ""
            revealSecrets -> password
            else -> SECRET_MASK
        }
        return listOf(
            Detail("$title server", json.optString("host").orEmpty()),
            Detail("$title port", json.optInt("port", 0).toString()),
            Detail("$title encryption", encryptionName(if (json.has("encryption")) json.optInt("encryption", -1)
                else if (json.optBoolean("starttls")) 1 else 0)),
            Detail("$title authentication", json.optInt("auth_type", 0).toString()),
            Detail("$title user name", json.optString("user").orEmpty()),
            Detail("$title password", shown, secret = true)
        )
    }

    private fun encryptionName(value: Int): String = when (value) {
        0 -> "SSL"
        1 -> "STARTTLS"
        2 -> "None"
        else -> "Unknown ($value)"
    }

    /** Build one account from FairEmail's documented account + identity JSON fields. */
    @JvmStatic
    fun create(name: String, address: String,
               incomingHost: String, incomingPort: Int, incomingEncryption: Int, incomingProtocol: Int,
               incomingUser: String, incomingPassword: String, incomingAuth: Int,
               outgoingHost: String, outgoingPort: Int, outgoingEncryption: Int,
               outgoingUser: String, outgoingPassword: String, outgoingAuth: Int): Account {
        val account = JSONObject()
            .put("id", 1).put("uuid", UUID.randomUUID().toString()).put("order", 0)
            .put("protocol", incomingProtocol).put("host", clean(incomingHost))
            .put("encryption", incomingEncryption).put("insecure", false).put("port", incomingPort)
            .put("auth_type", incomingAuth).put("user", clean(incomingUser)).put("password", incomingPassword)
            .put("name", clean(name)).put("synchronize", true).put("primary", true).put("poll_interval", 15)
            .put("ondemand", false).put("poll_exempted", false).put("notify", true).put("browse", true)
            .put("leave_on_server", true).put("client_delete", false).put("leave_deleted", true)
            .put("leave_on_device", true).put("auto_seen", true).put("keep_alive_noop", false)
            .put("partial_fetch", true).put("raw_fetch", false).put("ignore_size", false)
            .put("use_date", false).put("use_received", false).put("unicode", false)
            .put("folders", JSONArray())
        val identity = JSONObject()
            .put("id", 1).put("uuid", UUID.randomUUID().toString())
            .put("name", clean(name)).put("email", clean(address)).put("display", "")
            .put("host", clean(outgoingHost)).put("encryption", outgoingEncryption)
            .put("insecure", false).put("port", outgoingPort).put("auth_type", outgoingAuth)
            .put("user", clean(outgoingUser)).put("password", outgoingPassword)
            .put("login", true).put("use_ip", true).put("synchronize", true)
            .put("primary", true).put("self", true).put("sender_extra", false)
            .put("sender_extra_name", false).put("reply_extra_name", false).put("unicode", false)
            .put("octetmime", false).put("sign_default", false).put("encrypt_default", false)
            .put("sensitivity", 0)
        validatePair(account, identity)
        return Account(account, listOf(identity))
    }

    @JvmStatic
    fun encode(account: JSONObject, identities: List<JSONObject>): ByteArray {
        require(identities.size in 1..128)
        identities.forEach { validatePair(account, it) }
        val identityArray = JSONArray()
        identities.forEach { identityArray.put(it) }
        val envelope = JSONObject().put("fairmail_account", account).put("fairmail_identities", identityArray)
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Email entry too large" }
        return bytes
    }

    @JvmStatic
    fun decode(bytes: ByteArray): Account {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Invalid email entry" }
        val json = decodeUtf8(bytes)
        InputPolicy.jsonDepth(json)
        val envelope = JSONObject(json)
        val account = envelope.getJSONObject("fairmail_account")
        val identities = if (envelope.has("fairmail_identities")) {
            val stored = envelope.getJSONArray("fairmail_identities")
            (0 until stored.length()).map { stored.getJSONObject(it) }
        } else listOf(envelope.getJSONObject("fairmail_identity"))
        require(identities.size in 1..128)
        identities.forEach { validatePair(account, it) }
        return Account(JSONObject(account.toString()), identities.map { JSONObject(it.toString()) })
    }

    /** Parse FairEmail's gzip JSON backup-agent format or its plain JSON equivalent. */
    @JvmStatic
    fun importBackup(input: ByteArray, password: String? = null): List<Account> {
        require(input.isNotEmpty() && input.size <= MAX_IMPORT_BYTES) { "Backup too large" }
        val plain = when {
            input.size >= 2 && input[0] == 0x1f.toByte() && input[1] == 0x8b.toByte() ->
                GZIPInputStream(ByteArrayInputStream(input)).use { stream ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buf)
                        if (count < 0) break
                        require(out.size() + count <= MAX_IMPORT_BYTES) { "Backup too large" }
                        out.write(buf, 0, count)
                    }
                    out.toByteArray()
                }
            isPlainBackup(input) -> input.copyOf()
            else -> decryptFairEmailBackup(input, password)
        }
        try {
            val jsonText = decodeUtf8(plain)
            InputPolicy.jsonDepth(jsonText)
            val root = JSONObject(jsonText)
            val rows = if (root.has("accounts")) root.getJSONArray("accounts") else JSONArray().put(root)
            require(rows.length() <= InputPolicy.MAX_ENTRIES) { "Too many accounts" }
            val result = ArrayList<Account>(rows.length())
            for (index in 0 until rows.length()) {
                val account = rows.getJSONObject(index)
                val identities = account.getJSONArray("identities")
                require(identities.length() in 1..128) { "Invalid outgoing identities" }
                val identityList = (0 until identities.length()).map { identities.getJSONObject(it) }
                identityList.forEach { validatePair(account, it) }
                val primary = identityList.firstOrNull { it.optBoolean("primary", false) } ?: identityList.first()
                result.add(Account(JSONObject(account.toString()),
                    listOf(primary) + identityList.filterNot { it === primary }))
            }
            return result
        } catch (invalid: JSONException) {
            throw IllegalArgumentException("Invalid FairEmail backup", invalid)
        } finally { plain.fill(0) }
    }

    /** Generate a JSON file accepted by FairEmail's passwordless file-restore path. */
    @JvmStatic
    fun exportBackup(accounts: Collection<Account>): ByteArray {
        require(accounts.isNotEmpty() && accounts.size <= InputPolicy.MAX_ENTRIES)
        val rows = JSONArray()
        accounts.forEach { value ->
            require(value.identities.size in 1..128)
            value.identities.forEach { validatePair(value.account, it) }
            val identities = JSONArray()
            value.identities.forEach { identities.put(JSONObject(it.toString())) }
            val account = JSONObject(value.account.toString())
            if (account.optString("name").orEmpty().isBlank()) account.put("name", value.address)
            rows.put(account.put("folders", JSONArray())
                .put("identities", identities).put("contacts", JSONArray()))
        }
        val json = JSONObject().put("version", 1).put("accounts", rows)
            .put("answers", JSONArray()).put("searches", JSONArray()).put("certificates", JSONArray())
            .put("settings", JSONArray()).put("channels", JSONArray())
            .toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_IMPORT_BYTES)
        // FairEmail's file restore accepts this unencrypted JSON export directly. Its separate
        // Android Auto Backup agent uses the gzip-wrapped {version, settings, accounts} form.
        return json
    }

    private fun isPlainBackup(input: ByteArray): Boolean = try {
        val text = decodeUtf8(input)
        InputPolicy.jsonDepth(text)
        val root = JSONObject(text)
        root.has("accounts") || root.has("identities")
    } catch (_: Exception) { false }

    private fun decryptFairEmailBackup(input: ByteArray, password: String?): ByteArray {
        require(!password.isNullOrEmpty()) { "FairEmail backup password required" }
        var offset = 0
        var version = 0
        if (input.size >= 32 && String(input, 0, 15, Charsets.US_ASCII) == "___FairEmail___") {
            version = input[15].toInt() and 0xff
            require(version == 1) { "Unsupported FairEmail backup version" }
            offset = 16
        }
        val ivLength = if (version == 0) 16 else 12
        require(input.size >= offset + 16 + ivLength + 16) { "Invalid FairEmail backup" }
        val salt = input.copyOfRange(offset, offset + 16)
        val iv = input.copyOfRange(offset + 16, offset + 16 + ivLength)
        val ciphertext = input.copyOfRange(offset + 16 + ivLength, input.size)
        val passwordChars = password.toCharArray()
        val spec = PBEKeySpec(passwordChars, salt, if (version == 0) 65536 else 120000, 256)
        passwordChars.fill('\u0000')
        var derived: ByteArray? = null
        try {
            val algorithm = if (version == 0) "PBKDF2WithHmacSHA1" else "PBKDF2WithHmacSHA512"
            derived = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
            val cipher = if (version == 0) legacyFairEmailCipher() else Cipher.getInstance("AES/GCM/NoPadding")
            val key: SecretKey = SecretKeySpec(derived, "AES")
            if (version == 0) cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            else cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext).also { require(it.size <= MAX_IMPORT_BYTES) { "Backup too large" } }
        } catch (invalid: Exception) {
            throw IllegalArgumentException("Could not decrypt FairEmail backup", invalid)
        } finally {
            spec.clearPassword()
            salt.fill(0); iv.fill(0); ciphertext.fill(0); derived?.fill(0)
        }
    }

    /**
     * Read-only compatibility with FairEmail's headerless version 0 export.
     *
     * Upstream still selects this format in its password-protected writer (checked against
     * M66B/FairEmail commit 13403d91902ded9ee4c586ceb602e2332c469373). The file has no
     * authentication tag, so neither a padding check nor JSON validation makes it authenticated
     * encryption. Keep the exception local to this cipher factory; version 1 uses AES-GCM and
     * OfflineOTP's own vault/backups never use this legacy construction. See
     * docs/vault-security-hardening.md#fairemail-backup-compatibility-exception.
     */
    private fun legacyFairEmailCipher(): Cipher {
        // codeql[java/weak-cryptographic-algorithm] -- Required by FairEmail's v0 wire format; this is a documented compatibility exception, not an approved construction for new data.
        return Cipher.getInstance("AES/CBC/PKCS5Padding")
    }

    private fun validatePair(account: JSONObject, identity: JSONObject) {
        val protocol = if (account.has("protocol")) account.optInt("protocol", -1)
            else if (account.has("pop")) if (account.optBoolean("pop")) 1 else 0 else -1
        require(protocol in 0..1)
        validateConnection(account, "host", "port", "encryption", "auth_type", "user", "password")
        validateConnection(identity, "host", "port", "encryption", "auth_type", "user", "password")
        require(identity.optString("email").orEmpty().contains('@'))
        require(account.optString("name").orEmpty().isNotBlank() || identity.optString("email").orEmpty().isNotBlank())
        require(account.optInt("port", 0) in 1..65535 && identity.optInt("port", 0) in 1..65535)
        require(account.toString().length + identity.toString().length <= MAX_BYTES)
    }

    private fun validateConnection(json: JSONObject, host: String, port: String, encryption: String,
                                   auth: String, user: String, password: String) {
        require(json.optString(host).orEmpty().isNotBlank())
        require(json.optInt(port, 0) in 1..65535)
        val encryptionMode = if (json.has(encryption)) json.optInt(encryption, -1)
            else if (json.has("starttls")) if (json.optBoolean("starttls")) 1 else 0 else -1
        require(encryptionMode in 0..2)
        require(json.optInt(auth, -1) >= 0)
        require(json.has(user) && json.get(user) != JSONObject.NULL && json.has(password) && json.get(password) != JSONObject.NULL)
        require(json.optString(user).orEmpty().length <= MAX_CREDENTIAL_CHARS)
        require(json.optString(password).orEmpty().length <= MAX_CREDENTIAL_CHARS)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (invalid: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("Invalid FairEmail backup text", invalid)
    }

    // ---- Thunderbird interchange (same vault type) ----

    private data class ParsedThunderbirdHost(
        val host: String,
        val port: Int,
        val encryption: Int,
        val protocol: Int,
        val scheme: String
    )

    private fun parseThunderbirdHostname(raw: String): ParsedThunderbirdHost {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ParsedThunderbirdHost("localhost", 993, 0, 0, "imap")
        var scheme = ""
        var remainder = trimmed
        if (trimmed.contains("://")) {
            scheme = trimmed.substringBefore("://").lowercase()
            remainder = trimmed.substringAfter("://")
        } else {
            // No scheme: guess from host string
            val low = trimmed.lowercase()
            scheme = when {
                low.contains("pop") -> "pop"
                low.contains("smtp") -> "smtp"
                low.contains("imap") -> "imap"
                else -> "imap"
            }
        }
        // mailbox://nobody@Local Folders -> host is after @
        if (scheme == "mailbox") {
            val host = if (remainder.contains("@")) remainder.substringAfter("@").trim() else remainder.trim()
            val cleanHost = host.ifBlank { "localhost" }.take(255)
            return ParsedThunderbirdHost(cleanHost, 993, 0, 0, "imap")
        }
        // Remove path/query after host:port
        var hostPort = remainder.substringBefore("/").substringBefore("?")
        // If user@host form (e.g. user@mail.example.com), take host part
        if (hostPort.contains("@")) hostPort = hostPort.substringAfter("@")
        hostPort = hostPort.trim()
        var host = hostPort
        var portStr: String? = null
        // IPv6 [host]:port not expected for mail, keep simple
        if (hostPort.contains(":")) {
            host = hostPort.substringBefore(":").trim()
            portStr = hostPort.substringAfter(":").trim()
        }
        if (host.isBlank()) host = "mail.example.com"
        val port = portStr?.toIntOrNull() ?: defaultPortForScheme(scheme)
        val encryption = defaultEncryptionForPort(port)
        val protocol = if (scheme.contains("pop") || port == 110 || port == 995) 1 else 0
        return ParsedThunderbirdHost(host, port, encryption, protocol, scheme)
    }

    private fun defaultPortForScheme(scheme: String): Int = when (scheme.lowercase()) {
        "pop", "pop3" -> 995
        "smtp" -> 465
        "imap" -> 993
        else -> 993
    }

    private fun defaultEncryptionForPort(port: Int): Int = when (port) {
        993, 995, 465 -> 0 // SSL
        143, 110, 587, 25 -> 1 // STARTTLS
        else -> 0
    }

    private fun thunderbirdSchemeForIncoming(account: JSONObject): String {
        val proto = account.optInt("protocol", 0)
        return if (proto == 1) "pop" else "imap"
    }

    /**
     * Import Thunderbird's CSV export (Firefox/Thunderbird `Export Logins` format or the
     * raw `hostname` variant) into the *same* EMAIL vault entries FairEmail uses, so a
     * backup made from FairEmail can be restored as Thunderbird CSV and vice versa.
     *
     * Rows are grouped by `username` (the mailbox login, typically the email address);
     * the first IMAP/POP-like row in each group becomes the incoming connection, the
     * remaining SMTP-like rows become the outgoing identities. A single-row group is
     * duplicated as both sides so the resulting account is always valid.
     */
    @JvmStatic
    fun importThunderbirdCsv(bytes: ByteArray): List<Account> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMPORT_BYTES) { "Backup too large" }
        val parsed = ThunderbirdCsv.parse(bytes) as? ThunderbirdCsv.Result.Success
            ?: throw IllegalArgumentException("Invalid Thunderbird CSV")
        if (parsed.rows.isEmpty()) throw IllegalArgumentException("Invalid Thunderbird CSV")

        data class RowInfo(
            val hostname: String,
            val username: String,
            val password: String,
            val parsed: ParsedThunderbirdHost,
            val scheme: String
        )

        val rows = ArrayList<RowInfo>(parsed.rows.size)
        val hostnameIdx = parsed.columns.indexOfFirst { it.trim().equals("hostname", true) || it.trim().equals("host", true) || it.trim().equals("url", true) || it.trim().equals("login_uri", true) || it.trim().equals("origin", true) }
        val usernameIdx = parsed.columns.indexOfFirst { it.trim().equals("username", true) || it.trim().equals("login_username", true) || it.trim().equals("user", true) }
        val passwordIdx = parsed.columns.indexOfFirst { it.trim().equals("password", true) || it.trim().equals("login_password", true) || it.trim().equals("pass", true) }
        require(hostnameIdx >= 0 && usernameIdx >= 0 && passwordIdx >= 0) { "Invalid Thunderbird CSV" }

        for (row in parsed.rows) {
            val hostname = row.valueAt(hostnameIdx).trim()
            val username = row.valueAt(usernameIdx).trim()
            val password = row.valueAt(passwordIdx)
            if (hostname.isEmpty() || username.isEmpty()) throw IllegalArgumentException("Invalid Thunderbird CSV")
            require(hostname.length <= MAX_CREDENTIAL_CHARS && username.length <= MAX_CREDENTIAL_CHARS && password.length <= MAX_CREDENTIAL_CHARS) { "Invalid Thunderbird CSV" }
            val p = parseThunderbirdHostname(hostname)
            val scheme = if (p.scheme.isNotBlank()) p.scheme else if (hostname.lowercase().contains("smtp")) "smtp" else "imap"
            rows.add(RowInfo(hostname, username, password, p, scheme))
        }
        require(rows.size <= InputPolicy.MAX_ENTRIES) { "Too many accounts" }

        // Group by username (case-insensitive) – the mailbox identity
        val grouped = rows.groupBy { it.username.lowercase() }
        val result = ArrayList<Account>(grouped.size)
        for ((_, groupRows) in grouped) {
            // Pick incoming: first imap/pop/mailbox-like row, else first
            val incomingCandidates = groupRows.filter { it.scheme == "imap" || it.scheme == "pop" || it.scheme == "pop3" || it.scheme == "mailbox" || it.parsed.protocol == 1 || it.parsed.scheme == "imap" }
            val incoming = if (incomingCandidates.isNotEmpty()) incomingCandidates.first() else groupRows.first()
            val outgoingRows = groupRows.filter { it !== incoming && (it.scheme == "smtp" || it.parsed.host != incoming.parsed.host || groupRows.size > 1) }
            val effectiveOutgoing = if (outgoingRows.isEmpty()) listOf(incoming) else outgoingRows

            // Build incoming and outgoing identities
            val address = if (incoming.username.contains("@")) incoming.username else "${incoming.username}@${incoming.parsed.host}"
            val name = incoming.username.substringBefore("@").ifBlank { address }

            // First outgoing defines the primary identity for EmailMaterial.create
            val firstOut = effectiveOutgoing.first()
            val account = try {
                create(
                    name = name,
                    address = address,
                    incomingHost = incoming.parsed.host,
                    incomingPort = incoming.parsed.port,
                    incomingEncryption = incoming.parsed.encryption,
                    incomingProtocol = incoming.parsed.protocol,
                    incomingUser = incoming.username,
                    incomingPassword = incoming.password,
                    incomingAuth = 1,
                    outgoingHost = firstOut.parsed.host,
                    outgoingPort = firstOut.parsed.port,
                    outgoingEncryption = firstOut.parsed.encryption,
                    outgoingUser = firstOut.username,
                    outgoingPassword = firstOut.password,
                    outgoingAuth = 1
                )
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid Thunderbird CSV")
            }

            // Additional SMTP identities beyond the first
            if (effectiveOutgoing.size > 1) {
                val extraIdentities = ArrayList<JSONObject>()
                for (extra in effectiveOutgoing.drop(1)) {
                    val id = JSONObject()
                        .put("id", account.identities.size + extraIdentities.size + 1)
                        .put("uuid", UUID.randomUUID().toString())
                        .put("name", clean(name)).put("email", clean(address)).put("display", "")
                        .put("host", clean(extra.parsed.host)).put("encryption", extra.parsed.encryption)
                        .put("insecure", false).put("port", extra.parsed.port).put("auth_type", 1)
                        .put("user", clean(extra.username)).put("password", extra.password)
                        .put("login", true).put("use_ip", true).put("synchronize", true)
                        .put("primary", false).put("self", true).put("sender_extra", false)
                        .put("sender_extra_name", false).put("reply_extra_name", false).put("unicode", false)
                        .put("octetmime", false).put("sign_default", false).put("encrypt_default", false)
                        .put("sensitivity", 0)
                    extraIdentities.add(id)
                }
                val allIdentities = account.identities + extraIdentities
                // Re-validate and re-encode with extra identities
                result.add(Account(JSONObject(account.account.toString()), allIdentities))
            } else {
                result.add(account)
            }
        }
        return result
    }

    /**
     * Export the given EMAIL accounts as a Thunderbird-compatible CSV (Firefox `Export Logins`
     * format). Each vault account produces one row for its incoming connection and one row per
     * outgoing SMTP identity, so a FairEmail backup with N accounts and M identities round-trips
     * to Thunderbird as N incoming + M outgoing rows and back.
     */
    @JvmStatic
    fun exportThunderbirdCsv(accounts: Collection<Account>): ByteArray {
        require(accounts.isNotEmpty() && accounts.size <= InputPolicy.MAX_ENTRIES) { "No accounts" }
        val rows = ArrayList<List<String>>(accounts.size * 2)
        for (acct in accounts) {
            val incomingHost = acct.account.optString("host").orEmpty()
            val incomingPort = acct.account.optInt("port", 993)
            val incomingScheme = thunderbirdSchemeForIncoming(acct.account)
            val incomingUser = acct.account.optString("user").orEmpty()
            val incomingPass = acct.account.optString("password").orEmpty()
            val incomingUrl = "$incomingScheme://$incomingHost:$incomingPort"
            rows.add(listOf(incomingUrl, incomingUser, incomingPass, "Incoming (${incomingScheme.uppercase()})", "", genGuid(), "0", "0", "0"))

            for (identity in acct.identities) {
                val outHost = identity.optString("host").orEmpty()
                val outPort = identity.optInt("port", 465)
                val outUser = identity.optString("user").orEmpty()
                val outPass = identity.optString("password").orEmpty()
                val outUrl = "smtp://$outHost:$outPort"
                rows.add(listOf(outUrl, outUser, outPass, "Outgoing (SMTP)", "", genGuid(), "0", "0", "0"))
            }
        }
        // If an account's incoming and outgoing are identical (single-row Thunderbird import),
        // the above produces two identical-host rows; that is intentional so the export is lossless
        // for Thunderbird's per-server model, and a re-import groups them back into one account.
        return ThunderbirdCsv.exportCsv(ThunderbirdCsv.EXPORT_COLUMNS, rows)
    }

    private fun genGuid(): String = "{${UUID.randomUUID()}}"

    private fun clean(value: String): String = value.trim().also { InputPolicy.text(it, InputPolicy.MAX_TEXT_CHARS) }
}
