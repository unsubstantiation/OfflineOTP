package com.fairuniversal.offlineotp.Utilities

import java.security.SecureRandom
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Vault-key-bound local configuration, never a portable-preferences or backup format. */
object AutomaticBackupCredentialCodec {
    const val MAX_PLAINTEXT_BYTES = 65536
    private val magic = byteArrayOf(0x41, 0x42, 0x50, 1)
    private val domain = "OfflineOTP automatic backup credentials v1".toByteArray(Charsets.UTF_8)
    fun seal(key: SecretKey, plaintext: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT_BYTES)
        val nonce = ByteArray(12).also(random::nextBytes)
        val derived = derive(key)
        return try {
            val header = magic + nonce
            header + CryptoPrimitives.encryptWithAad(SecretKeySpec(derived, "AES"), nonce, plaintext, header)
        } finally { derived.fill(0) }
    }
    fun open(key: SecretKey, encrypted: ByteArray): ByteArray {
        require(encrypted.size in 33..MAX_PLAINTEXT_BYTES + 32 && encrypted.copyOfRange(0, 4).contentEquals(magic))
        val derived = derive(key)
        return try {
            CryptoPrimitives.decryptWithAad(SecretKeySpec(derived, "AES"), encrypted.copyOfRange(4, 16),
                encrypted.copyOfRange(16, encrypted.size), encrypted.copyOfRange(0, 16))
        } finally { derived.fill(0) }
    }
    class Configuration(val folder: String, val password: CharArray, val minutes: Int, val keep: Int,
                        val recipientKeys: List<ByteArray> = emptyList(), val requiresSigning: Boolean = false,
                        val vaultCopy: Boolean = false) : AutoCloseable {
        override fun close() { password.fill('\u0000'); recipientKeys.forEach { it.fill(0) } }
        override fun toString() = "AutomaticBackupConfiguration(redacted)"
    }
    fun configurationDocument(folder: String, password: CharArray, minutes: Int, keep: Int): ByteArray {
        require(folder.length in 1..4096 && password.size in 1..InputPolicy.MAX_TEXT_CHARS && minutes in 1..1440 && keep in 1..100)
        return JSONObject().put("version", 1).put("folder", folder).put("password", password.concatToString())
            .put("minutes", minutes).put("keep", keep).toString().toByteArray(Charsets.UTF_8)
    }
    /** Version 3 is exclusively a saved-vault copy, with no export password or recipients. */
    fun snapshotConfigurationDocument(folder: String, minutes: Int, keep: Int): ByteArray {
        require(folder.length in 1..4096 && minutes in 1..1440 && keep in 1..100)
        return JSONObject().put("version", 3).put("mode", "vault-copy").put("folder", folder)
            .put("password", "").put("minutes", minutes).put("keep", keep).toString().toByteArray(Charsets.UTF_8)
    }

    // Public certificates are snapshotted, not paths that can later resolve to different recipients.
    const val MAX_RECIPIENT_BYTES = 32768
    fun validRecipients(keys: List<ByteArray>): Boolean = keys.size in 1..OpenPgpKeyFilePolicy.MAX_RECIPIENTS &&
        keys.all { it.isNotEmpty() } && keys.sumOf { it.size.toLong() } <= MAX_RECIPIENT_BYTES &&
        keys.indices.none { i -> (0 until i).any { keys[i].contentEquals(keys[it]) } }

    fun recipientConfigurationDocument(folder: String, keys: List<ByteArray>, minutes: Int, keep: Int, requiresSigning: Boolean = false): ByteArray {
        require(folder.length in 1..4096 && minutes in 1..1440 && keep in 1..100 && validRecipients(keys))
        return JSONObject().put("version", 2).put("mode", "openpgp").put("folder", folder).put("password", "")
            .put("minutes", minutes).put("keep", keep)
            .put("requiresSigning", requiresSigning)
            .put("recipientKeys", org.json.JSONArray(keys.map { java.util.Base64.getEncoder().encodeToString(it) }))
            .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_PLAINTEXT_BYTES) }
    }
    fun parseConfiguration(document: ByteArray): Configuration {
        require(document.isNotEmpty() && document.size <= MAX_PLAINTEXT_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(document)).toString()
        InputPolicy.jsonDepth(text)
        val json = JSONObject(text)
        fun integer(key: String): Int {
            val value = json.opt(key)
            require(value is Int || value is Long)
            val number = (value as Number).toLong()
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return number.toInt()
        }
        require(json.opt("password") is String && json.opt("folder") is String)
        val version = integer("version")
        require(version in 1..3)
        val password = json.getString("password").toCharArray()
        val keys = arrayListOf<ByteArray>()
        try {
            val folder = json.getString("folder"); val minutes = integer("minutes"); val keep = integer("keep")
            require(folder.length in 1..4096 && minutes in 1..1440 && keep in 1..100)
            if (version == 1) require(password.size in 1..InputPolicy.MAX_TEXT_CHARS)
            else if (version == 3) {
                require(json.opt("mode") == "vault-copy" && password.isEmpty() && !json.has("recipientKeys"))
            } else {
                require(json.opt("mode") is String && json.getString("mode") == "openpgp" && password.isEmpty())
                val array = json.getJSONArray("recipientKeys")
                require(array.length() in 1..OpenPgpKeyFilePolicy.MAX_RECIPIENTS)
                for (i in 0 until array.length()) {
                    val encoded = array.opt(i) as? String ?: error("Invalid recipient")
                    require(encoded.length <= MAX_RECIPIENT_BYTES * 2)
                    keys.add(java.util.Base64.getDecoder().decode(encoded))
                }
                require(validRecipients(keys))
            }
            require(!json.has("requiresSigning") || json.opt("requiresSigning") is Boolean)
            val requiresSigning = json.opt("requiresSigning") == true
            require(!requiresSigning || version == 2)
            return Configuration(folder, password.copyOf(), minutes, keep, keys.map { it.copyOf() }, requiresSigning, version == 3)
        } finally { password.fill('\u0000'); keys.forEach { it.fill(0) } }
    }
    private fun derive(key: SecretKey): ByteArray {
        val encoded = requireNotNull(key.encoded)
        try {
            require(encoded.size in listOf(16, 24, 32))
            return Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(encoded, "HmacSHA256")); doFinal(domain)
            }
        } finally { encoded.fill(0) }
    }
}
