package org.shadowice.flocke.andotp.Utilities

import java.nio.ByteBuffer
import java.util.Arrays

/** Validates current backup framing before any expensive password derivation. */
class EncryptedBackupHeader private constructor(
    private val iterations: Int,
    private val salt: ByteArray,
    private val encrypted: ByteArray
) {
    fun getIterations(): Int = iterations

    /** A copy of the salt, so callers cannot alias the parsed untrusted bytes. */
    fun getSalt(): ByteArray = salt.clone()

    /** A copy of the nonce/ciphertext, so callers cannot alias the parsed untrusted bytes. */
    fun getEncrypted(): ByteArray = encrypted.clone()

    companion object {
        private const val ITERATION_BYTES = 4
        private const val SALT_BYTES = 12
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        const val MIN_BACKUP_BYTES = ITERATION_BYTES + SALT_BYTES + NONCE_BYTES + TAG_BYTES

        // Read compatibility includes historical counts below the current writer's range.
        // Counts above this ceiling require explicit review, not unbounded CPU consumption.
        const val MAX_ITERATIONS = 1_000_000

        /** Returns null-free framing or throws IllegalArgumentException before any derivation. */
        @JvmStatic
        fun parse(data: ByteArray?): EncryptedBackupHeader {
            if (data == null || data.size < MIN_BACKUP_BYTES) {
                throw IllegalArgumentException("Truncated encrypted backup")
            }
            if (data.size > BoundedInputReader.MAX_DOCUMENT_BYTES) {
                throw IllegalArgumentException("Encrypted backup exceeds size limit")
            }
            val iterationCount = ByteBuffer.wrap(data, 0, ITERATION_BYTES).getInt()
            if (iterationCount < 1 || iterationCount > MAX_ITERATIONS) {
                throw IllegalArgumentException("Unsupported backup iteration count")
            }
            return EncryptedBackupHeader(iterationCount,
                    Arrays.copyOfRange(data, ITERATION_BYTES, ITERATION_BYTES + SALT_BYTES),
                    Arrays.copyOfRange(data, ITERATION_BYTES + SALT_BYTES, data.size))
        }
    }
}
