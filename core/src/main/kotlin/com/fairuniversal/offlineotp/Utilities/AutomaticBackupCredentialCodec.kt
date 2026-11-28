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
    private val configuration = com.fairuniversal.offlineotp.domain.AutomaticBackupConfigurationCodec()
    fun configurationDocument(folder: String, password: CharArray, minutes: Int, keep: Int): ByteArray =
        requireNotNull(configuration.encode(folder, password.concatToString(), minutes, keep, emptyList(), false, false)).toByteArray(Charsets.UTF_8)
    /** Version 3 is exclusively a saved-vault copy, with no export password or recipients. */
    fun snapshotConfigurationDocument(folder: String, minutes: Int, keep: Int): ByteArray =
        requireNotNull(configuration.encode(folder, "", minutes, keep, emptyList(), false, true)).toByteArray(Charsets.UTF_8)
    const val MAX_RECIPIENT_BYTES = 32768
    fun validRecipients(keys: List<ByteArray>): Boolean = com.fairuniversal.offlineotp.domain.BackupAutomationPolicy().validRecipients(keys)
    fun recipientConfigurationDocument(folder: String, keys: List<ByteArray>, minutes: Int, keep: Int, requiresSigning: Boolean = false): ByteArray {
        require(validRecipients(keys))
        return requireNotNull(configuration.encode(folder, "", minutes, keep,
            keys.map { java.util.Base64.getEncoder().encodeToString(it) }, requiresSigning, false)).toByteArray(Charsets.UTF_8)
    }
    fun configurationDocument(folder: String, password: CharArray, minutes: Int, keep: Int,
                              keys: List<ByteArray>, requiresSigning: Boolean, vaultCopy: Boolean): ByteArray {
        require(keys.isEmpty() || validRecipients(keys))
        return requireNotNull(configuration.encode(folder, password.concatToString(), minutes, keep,
            keys.map { java.util.Base64.getEncoder().encodeToString(it) }, requiresSigning, vaultCopy)).toByteArray(Charsets.UTF_8)
    }
    fun parseConfiguration(document: ByteArray): Configuration {
        require(document.isNotEmpty() && document.size <= MAX_PLAINTEXT_BYTES)
        val text = document.decodeToString(throwOnInvalidSequence = true)
        val json = JSONObject(requireNotNull(configuration.canonical(text)))
        val keys = arrayListOf<ByteArray>()
        var password: CharArray? = null
        try {
            password = json.getString("password").toCharArray()
            val array = json.opt("recipientKeys") as? org.json.JSONArray
            if (array != null) for (i in 0 until array.length()) keys.add(java.util.Base64.getDecoder().decode(array.getString(i)))
            return Configuration(json.getString("folder"), password, json.getInt("minutes"), json.getInt("keep"),
                keys.toList(), json.optBoolean("requiresSigning", false), json.getInt("version") == 3).also { password = null; keys.clear() }
        } finally { password?.fill('\u0000'); keys.forEach { it.fill(0) } }
    }
    private fun derive(key: SecretKey): ByteArray {
        val encoded = requireNotNull(key.encoded).copyOf()
        try {
            require(encoded.size in listOf(16, 24, 32))
            return Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(encoded, "HmacSHA256")); doFinal(domain)
            }
        } finally { encoded.fill(0) }
    }
}
