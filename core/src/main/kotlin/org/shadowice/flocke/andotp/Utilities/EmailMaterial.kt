package org.shadowice.flocke.andotp.Utilities

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
            val cipher = Cipher.getInstance(if (version == 0) "AES/CBC/PKCS5Padding" else "AES/GCM/NoPadding")
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

    private fun clean(value: String): String = value.trim().also { InputPolicy.text(it, InputPolicy.MAX_TEXT_CHARS) }
}
