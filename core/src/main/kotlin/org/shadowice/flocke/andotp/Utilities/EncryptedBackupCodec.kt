package org.shadowice.flocke.andotp.Utilities

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Password-encrypted backup framing, shared by the Android app and the desktop client so an
 * encrypted backup produced on either platform is byte-compatible.
 *
 * Current format (matching the historical writer):
 * `[4-byte big-endian PBKDF2 iterations][12-byte salt][12-byte GCM nonce][ciphertext][16-byte tag]`,
 * parsed by [EncryptedBackupHeader]. The legacy format is AES-GCM data with no header and a key
 * derived as the plain SHA-256 of the password; it remains readable but is never newly written.
 *
 * All key derivation and AES-GCM work is delegated to [CryptoPrimitives]; this object only owns the
 * iteration/salt selection and the byte layout. It performs no file I/O and has no Android types.
 */
object EncryptedBackupCodec {

    /**
     * Encrypts [plain] with a fresh random iteration count and salt. Throws on an empty password or
     * a crypto failure; callers decide how to surface that (the Android writer maps it to `false`).
     */
    @JvmStatic
    fun seal(password: String, plain: String): ByteArray {
        require(password.isNotEmpty()) { "Backup password must not be empty" }
        val iter = CryptoPrimitives.generateRandomIterations()
        val salt = CryptoPrimitives.generateRandom(Constants.ENCRYPTION_IV_LENGTH)

        val key = CryptoPrimitives.generateSymmetricKeyPBKDF2(password, iter, salt)
        val encrypted = CryptoPrimitives.encrypt(key, plain.toByteArray(StandardCharsets.UTF_8))

        // Identical layout to the previous BackupHelper writer: int iterations, then the 12-byte
        // salt, then the nonce-prefixed AES-GCM blob.
        val data = ByteBuffer
            .allocate(Constants.INT_LENGTH + Constants.ENCRYPTION_IV_LENGTH + encrypted.size)
            .putInt(iter)
            .put(salt)
            .put(encrypted)
        return data.array()
    }

    /**
     * Decrypts a password backup. [oldFormat] selects the legacy header-less SHA-256 key path.
     * A wrong password or tampered bytes surface as the underlying JCA exception (for example
     * `BadPaddingException`); truncated framing throws `IllegalArgumentException`.
     */
    @JvmStatic
    fun open(password: String, data: ByteArray, oldFormat: Boolean): String {
        val decrypted = if (oldFormat) {
            val key = CryptoPrimitives.generateSymmetricKeyFromPassword(password)
            CryptoPrimitives.decrypt(key, data)
        } else {
            val header = EncryptedBackupHeader.parse(data)
            val key = CryptoPrimitives.generateSymmetricKeyPBKDF2(
                password, header.getIterations(), header.getSalt())
            CryptoPrimitives.decrypt(key, header.getEncrypted())
        }
        try {
            return String(decrypted, StandardCharsets.UTF_8)
        } finally {
            decrypted.fill(0)
        }
    }
}
