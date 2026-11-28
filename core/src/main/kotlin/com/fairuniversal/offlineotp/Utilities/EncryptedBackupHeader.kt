package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.domain.VaultProtocol
import com.fairuniversal.offlineotp.domain.ProtocolKdf
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
            val descriptor = requireNotNull(VaultProtocol().makeBackupHeader(salt, memoryKiB, iterations, parallelism).header) { "Invalid backup parameters" }
            require(encrypted.size >= NONCE_BYTES + TAG_BYTES && encrypted.size <= BoundedInputReader.MAX_DOCUMENT_BYTES - ARGON2_HEADER_BYTES) { "Invalid backup size" }
            return descriptor.bytes() + encrypted
        }

        /** Returns null-free framing or throws IllegalArgumentException before any derivation. */
        @JvmStatic
        fun parse(data: ByteArray?): EncryptedBackupHeader {
            requireNotNull(data) { "Truncated encrypted backup" }
            val descriptor = requireNotNull(VaultProtocol().backupHeader(data, data.size).header) { "Invalid encrypted backup framing" }
            return EncryptedBackupHeader(
                if (descriptor.kdf == ProtocolKdf.ARGON2ID) Kdf.ARGON2ID else Kdf.PBKDF2,
                descriptor.iterations, descriptor.memoryKiB, descriptor.parallelism,
                descriptor.salt(), data.copyOfRange(descriptor.encryptedOffset, data.size))
        }
    }
}
