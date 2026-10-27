package org.shadowice.flocke.andotp.Utilities

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A bounded, authenticated wrapper for an existing exportable vault key. A FIDO2 PRF output is
 * never stored. This is an alternative unlock path, not a second factor for password unlock.
 */
object HardwareUnlockEnvelope {
    const val SALT_BYTES = 32
    const val PRF_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val MAX_CREDENTIAL_ID = 1024
    private val magic = byteArrayOf(0x4f, 0x54, 0x50, 0x01)
    private val domain = "OfflineOTP hardware unlock key v1".toByteArray(StandardCharsets.UTF_8)

    /** Public request parameters. The credential ID and PRF salt are not secret. */
    class Request constructor(val credentialId: ByteArray, val salt: ByteArray) {
        override fun toString(): String = "HardwareUnlockRequest(redacted)"
    }

    fun seal(credentialId: ByteArray, salt: ByteArray, prf: ByteArray, vaultKey: ByteArray,
             random: SecureRandom = SecureRandom()): ByteArray {
        require(credentialId.isNotEmpty() && credentialId.size <= MAX_CREDENTIAL_ID)
        require(salt.size == SALT_BYTES && prf.size == PRF_BYTES)
        require(vaultKey.size == 16 || vaultKey.size == 24 || vaultKey.size == 32)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(magic.size + 2 + credentialId.size + SALT_BYTES + NONCE_BYTES)
            .put(magic).putShort(credentialId.size.toShort()).put(credentialId).put(salt).put(nonce).array()
        val wrappingKey = derive(prf)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(vaultKey)
        } finally {
            wrappingKey.fill(0)
        }
    }

    fun request(envelope: ByteArray): Request? {
        val parsed = parse(envelope) ?: return null
        return Request(parsed.credentialId, parsed.salt)
    }

    /** Returns owned vault-key bytes only on authenticated decryption, or null for any failure. */
    fun open(envelope: ByteArray, prf: ByteArray): ByteArray? {
        if (prf.size != PRF_BYTES) return null
        val parsed = parse(envelope) ?: return null
        val wrappingKey = derive(prf)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, parsed.nonce))
            cipher.updateAAD(envelope, 0, parsed.headerLength)
            val result = cipher.doFinal(envelope, parsed.headerLength, envelope.size - parsed.headerLength)
            if (result.size == 16 || result.size == 24 || result.size == 32) result
            else { result.fill(0); null }
        } catch (_: GeneralSecurityException) {
            null
        } finally {
            wrappingKey.fill(0)
        }
    }

    private fun derive(prf: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prf, "HmacSHA256"))
        return mac.doFinal(domain)
    }

    private class Parsed(val credentialId: ByteArray, val salt: ByteArray,
                         val nonce: ByteArray, val headerLength: Int)

    private fun parse(value: ByteArray): Parsed? {
        val minHeader = magic.size + 2 + 1 + SALT_BYTES + NONCE_BYTES
        if (value.size < minHeader + 16 + TAG_BYTES ||
            value.size > magic.size + 2 + MAX_CREDENTIAL_ID + SALT_BYTES + NONCE_BYTES + 32 + TAG_BYTES)
            return null
        val buffer = ByteBuffer.wrap(value)
        for (byte in magic) if (buffer.get() != byte) return null
        val idLength = buffer.short.toInt() and 0xffff
        if (idLength !in 1..MAX_CREDENTIAL_ID) return null
        val headerLength = magic.size + 2 + idLength + SALT_BYTES + NONCE_BYTES
        val encryptedLength = value.size - headerLength
        if (encryptedLength != 16 + TAG_BYTES && encryptedLength != 24 + TAG_BYTES &&
            encryptedLength != 32 + TAG_BYTES) return null
        val id = ByteArray(idLength).also(buffer::get)
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        return Parsed(id, salt, nonce, headerLength)
    }
}
