package com.fairuniversal.offlineotp.Utilities

import java.nio.ByteBuffer
import java.util.Arrays
import javax.crypto.SecretKey

/**
 * Validates encrypted-backup framing before any expensive password derivation.
 *
 * Supports both the current versioned Argon2id format (`"OOTP" || 0x01 || BE32(memoryKiB) ||
 * BE32(iterations) || parallelism || 16-byte salt || nonce || ciphertext || tag`) and legacy
 * PBKDF2 backups (`BE32(iterations in 1..1,000,000) || 12-byte salt || nonce || ciphertext || tag`)
 * so existing `.json.aes` exports continue to restore seamlessly.
 */
class EncryptedBackupHeader private constructor(
    private val kdf: Kdf,
    private val iterations: Int,
    private val memoryKiB: Int,
    private val parallelism: Int,
    private val salt: ByteArray,
    private val encrypted: ByteArray
) {
    enum class Kdf {
        ARGON2ID,
        PBKDF2
    }

    fun getKdf(): Kdf = kdf

    fun isArgon2id(): Boolean = kdf == Kdf.ARGON2ID

    fun getIterations(): Int = iterations

    fun getMemoryKiB(): Int = memoryKiB

    fun getParallelism(): Int = parallelism

    /** A copy of the salt, so callers cannot alias the parsed untrusted bytes. */
    fun getSalt(): ByteArray = salt.clone()

    /** A copy of the nonce/ciphertext, so callers cannot alias the parsed untrusted bytes. */
    fun getEncrypted(): ByteArray = encrypted.clone()

    /** Derives the backup AES-256 key matching this header's KDF and parameters. */
    fun deriveKey(password: String?): SecretKey = when (kdf) {
        Kdf.ARGON2ID -> CryptoPrimitives.generateSymmetricKeyArgon2id(
            password = password,
            salt = salt,
            memoryKiB = memoryKiB,
            iterations = iterations,
            parallelism = parallelism
        )
        Kdf.PBKDF2 -> CryptoPrimitives.generateSymmetricKeyPBKDF2(
            password = password,
            iter = iterations,
            salt = salt
        )
    }

    companion object {
        private const val MAGIC_0: Byte = 0x4F // 'O'
        private const val MAGIC_1: Byte = 0x4F // 'O'
        private const val MAGIC_2: Byte = 0x54 // 'T'
        private const val MAGIC_3: Byte = 0x50 // 'P'
        const val FORMAT_VERSION_ARGON2ID = 1

        private const val ITERATION_BYTES = 4
        private const val SALT_BYTES = 12
        const val ARGON2_SALT_BYTES = 16
        const val ARGON2_HEADER_BYTES = 4 + 1 + 4 + 4 + 1 + ARGON2_SALT_BYTES // 30 bytes
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        const val MIN_BACKUP_BYTES = ITERATION_BYTES + SALT_BYTES + NONCE_BYTES + TAG_BYTES
        const val MIN_ARGON2_BACKUP_BYTES = ARGON2_HEADER_BYTES + NONCE_BYTES + TAG_BYTES

        // Legacy PBKDF2 read compatibility includes historical counts below the old writer's range.
        // Counts above this ceiling require explicit review, not unbounded CPU consumption.
        const val MAX_ITERATIONS = 1_000_000

        @JvmStatic
        fun frameArgon2id(
            memoryKiB: Int,
            iterations: Int,
            parallelism: Int,
            salt: ByteArray,
            encrypted: ByteArray
        ): ByteArray {
            require(salt.size == ARGON2_SALT_BYTES) { "Argon2id backup salt must be $ARGON2_SALT_BYTES bytes" }
            Argon2id.validateParameters(
                password = ByteArray(0),
                salt = salt,
                memoryKiB = memoryKiB,
                iterations = iterations,
                parallelism = parallelism,
                tagLength = Constants.ARGON2_HASH_LENGTH
            )
            return ByteBuffer.allocate(ARGON2_HEADER_BYTES + encrypted.size)
                .put(MAGIC_0)
                .put(MAGIC_1)
                .put(MAGIC_2)
                .put(MAGIC_3)
                .put(FORMAT_VERSION_ARGON2ID.toByte())
                .putInt(memoryKiB)
                .putInt(iterations)
                .put(parallelism.toByte())
                .put(salt)
                .put(encrypted)
                .array()
        }

        /** Returns null-free framing or throws IllegalArgumentException before any derivation. */
        @JvmStatic
        fun parse(data: ByteArray?): EncryptedBackupHeader {
            if (data == null || data.size < MIN_BACKUP_BYTES) {
                throw IllegalArgumentException("Truncated encrypted backup")
            }
            if (data.size > BoundedInputReader.MAX_DOCUMENT_BYTES) {
                throw IllegalArgumentException("Encrypted backup exceeds size limit")
            }

            if (data[0] == MAGIC_0 && data[1] == MAGIC_1 && data[2] == MAGIC_2 && data[3] == MAGIC_3) {
                if (data.size < MIN_ARGON2_BACKUP_BYTES) {
                    throw IllegalArgumentException("Truncated encrypted backup")
                }
                val version = data[4].toInt() and 0xFF
                if (version != FORMAT_VERSION_ARGON2ID) {
                    throw IllegalArgumentException("Unsupported encrypted backup version")
                }
                val params = ByteBuffer.wrap(data, 5, 8)
                val memoryKiB = params.getInt()
                val iterations = params.getInt()
                val parallelism = data[13].toInt() and 0xFF
                val salt = Arrays.copyOfRange(data, 14, 14 + ARGON2_SALT_BYTES)
                Argon2id.validateParameters(
                    password = ByteArray(0),
                    salt = salt,
                    memoryKiB = memoryKiB,
                    iterations = iterations,
                    parallelism = parallelism,
                    tagLength = Constants.ARGON2_HASH_LENGTH
                )
                return EncryptedBackupHeader(
                    kdf = Kdf.ARGON2ID,
                    iterations = iterations,
                    memoryKiB = memoryKiB,
                    parallelism = parallelism,
                    salt = salt,
                    encrypted = Arrays.copyOfRange(data, ARGON2_HEADER_BYTES, data.size)
                )
            }

            val iterationCount = ByteBuffer.wrap(data, 0, ITERATION_BYTES).getInt()
            if (iterationCount < 1 || iterationCount > MAX_ITERATIONS) {
                throw IllegalArgumentException("Unsupported backup iteration count")
            }
            return EncryptedBackupHeader(
                kdf = Kdf.PBKDF2,
                iterations = iterationCount,
                memoryKiB = 0,
                parallelism = 0,
                salt = Arrays.copyOfRange(data, ITERATION_BYTES, ITERATION_BYTES + SALT_BYTES),
                encrypted = Arrays.copyOfRange(data, ITERATION_BYTES + SALT_BYTES, data.size)
            )
        }
    }
}
