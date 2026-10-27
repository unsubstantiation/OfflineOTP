package org.shadowice.flocke.andotp.Utilities

import java.nio.ByteBuffer
import javax.crypto.spec.SecretKeySpec

/**
 * A session-only key for the existing password-encrypted backup format. The login password is
 * never stored here. Each password unlock creates a fresh salt and key; each file gets a fresh
 * AES-GCM nonce. Copies are owned and must be closed by their caller.
 */
class LoginBackupKey private constructor(
    key: ByteArray,
    salt: ByteArray,
    private val iterations: Int,
    private val budget: EncryptionBudget = EncryptionBudget()
) : AutoCloseable {
    private var material: ByteArray? = key.clone()
    private val salt: ByteArray = salt.clone()

    /** Shared by all copies; at most 2^16 random GCM nonces are used under one session key. */
    private class EncryptionBudget {
        private var used = 0
        @Synchronized fun take(): Boolean {
            if (used >= 65_536) return false
            used++
            return true
        }
    }

    init {
        require(key.size == 32 && salt.size == Constants.ENCRYPTION_IV_LENGTH)
        require(iterations in 1..EncryptedBackupHeader.MAX_ITERATIONS)
    }

    @Synchronized fun copy(): LoginBackupKey? = material?.let { LoginBackupKey(it, salt, iterations, budget) }

    /** Encrypts a complete snapshot in the existing, portable backup framing. */
    @Synchronized fun encrypt(plaintext: ByteArray): ByteArray {
        val bytes = checkNotNull(material) { "Backup key has been cleared" }.clone()
        try {
            check(budget.take()) { "Backup session exhausted; unlock with the password again" }
            val encrypted = CryptoPrimitives.encrypt(SecretKeySpec(bytes, "AES"), plaintext)
            return ByteBuffer.allocate(Constants.INT_LENGTH + salt.size + encrypted.size)
                .putInt(iterations).put(salt).put(encrypted).array()
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized override fun close() {
        material?.fill(0)
        material = null
    }

    override fun toString(): String = "LoginBackupKey(redacted)"

    companion object {
        /** Uses the existing backup KDF and format so older compatible readers can restore it. */
        fun derive(password: String): LoginBackupKey {
            require(password.isNotEmpty() && password.length <= InputPolicy.MAX_TEXT_CHARS)
            val salt = CryptoPrimitives.generateRandom(Constants.ENCRYPTION_IV_LENGTH)
            val iterations = Constants.PBKDF2_MAX_BACKUP_ITERATIONS
            val derived = CryptoPrimitives.generateSymmetricKeyPBKDF2(password, iterations, salt).encoded
            try {
                return LoginBackupKey(derived, salt, iterations)
            } finally {
                derived.fill(0)
            }
        }
    }
}
