package com.fairuniversal.offlineotp.Utilities

import javax.crypto.spec.SecretKeySpec

/**
 * A session-only Argon2id key for the portable password-encrypted backup format. The login password
 * is never stored here. Each password unlock creates a fresh 16-byte salt and 256-bit key; each
 * file gets a fresh AES-GCM nonce. Copies are owned and must be closed by their caller.
 */
class LoginBackupKey private constructor(
    key: ByteArray,
    salt: ByteArray,
    private val memoryKiB: Int,
    private val iterations: Int,
    private val parallelism: Int,
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
        require(key.size == Constants.ARGON2_HASH_LENGTH && salt.size == Constants.ARGON2_SALT_LENGTH)
        Argon2id.validateParameters(
            password = ByteArray(0),
            salt = salt,
            memoryKiB = memoryKiB,
            iterations = iterations,
            parallelism = parallelism,
            tagLength = Constants.ARGON2_HASH_LENGTH
        )
    }

    @Synchronized fun copy(): LoginBackupKey? =
        material?.let { LoginBackupKey(it, salt, memoryKiB, iterations, parallelism, budget) }

    /** Encrypts a complete snapshot in the portable Argon2id backup framing. */
    @Synchronized fun encrypt(plaintext: ByteArray): ByteArray {
        val bytes = checkNotNull(material) { "Backup key has been cleared" }.clone()
        try {
            check(budget.take()) { "Backup session exhausted; unlock with the password again" }
            val encrypted = CryptoPrimitives.encrypt(SecretKeySpec(bytes, "AES"), plaintext)
            return EncryptedBackupHeader.frameArgon2id(
                memoryKiB = memoryKiB,
                iterations = iterations,
                parallelism = parallelism,
                salt = salt,
                encrypted = encrypted
            )
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
        /** Derives a fresh Argon2id backup key for the current unlocked session. */
        fun derive(password: String): LoginBackupKey {
            require(password.isNotEmpty() && password.length <= InputPolicy.MAX_TEXT_CHARS)
            val salt = CryptoPrimitives.generateRandom(Constants.ARGON2_SALT_LENGTH)
            val memoryKiB = Constants.ARGON2_MEMORY_KIB
            val iterations = Constants.ARGON2_ITERATIONS
            val parallelism = Constants.ARGON2_PARALLELISM
            val derived = CryptoPrimitives.generateSymmetricKeyArgon2id(
                password = password,
                salt = salt,
                memoryKiB = memoryKiB,
                iterations = iterations,
                parallelism = parallelism
            ).encoded
            try {
                return LoginBackupKey(derived, salt, memoryKiB, iterations, parallelism)
            } finally {
                derived.fill(0)
            }
        }
    }
}
