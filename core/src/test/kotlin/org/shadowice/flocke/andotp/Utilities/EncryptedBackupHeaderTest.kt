/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the encrypted-backup framing check that runs before any derivation.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class EncryptedBackupHeaderTest {
    private fun framed(iterations: Int, salt: ByteArray, encrypted: ByteArray): ByteArray {
        val data = ByteArray(4 + salt.size + encrypted.size)
        ByteBuffer.wrap(data, 0, 4).putInt(iterations)
        System.arraycopy(salt, 0, data, 4, salt.size)
        System.arraycopy(encrypted, 0, data, 4 + salt.size, encrypted.size)
        return data
    }

    @Test
    fun parsesWellFormedFraming() {
        val salt = ByteArray(12) { it.toByte() }
        val encrypted = ByteArray(12 + 16) { (it * 3).toByte() }  // nonce + tag
        val header = EncryptedBackupHeader.parse(framed(150000, salt, encrypted))

        assertEquals(150000, header.getIterations())
        assertArrayEquals(salt, header.getSalt())
        assertArrayEquals(encrypted, header.getEncrypted())
    }

    @Test
    fun copiesAreIndependentOfTheParsedBytes() {
        val salt = ByteArray(12) { 1 }
        val encrypted = ByteArray(28) { 2 }
        val data = framed(150000, salt, encrypted)
        val header = EncryptedBackupHeader.parse(data)
        data.fill(0)
        assertArrayEquals(salt, header.getSalt())
        assertArrayEquals(encrypted, header.getEncrypted())
    }

    @Test
    fun truncatedFramingIsRejected() {
        for (size in intArrayOf(0, 4, 15, 16, EncryptedBackupHeader.MIN_BACKUP_BYTES - 1)) {
            try {
                EncryptedBackupHeader.parse(ByteArray(size))
                throw AssertionError("truncated backup accepted at $size bytes")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun iterationBoundsAreEnforced() {
        for (iterations in intArrayOf(0, -1, EncryptedBackupHeader.MAX_ITERATIONS + 1)) {
            val data = framed(iterations, ByteArray(12), ByteArray(28))
            try {
                EncryptedBackupHeader.parse(data)
                throw AssertionError("iteration count accepted: $iterations")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(150000, EncryptedBackupHeader.parse(
            framed(150000, ByteArray(12), ByteArray(28))).getIterations())
        assertEquals(1000000, EncryptedBackupHeader.parse(
            framed(EncryptedBackupHeader.MAX_ITERATIONS, ByteArray(12), ByteArray(28))).getIterations())
    }

    @Test
    fun parsesWellFormedArgon2idFramingAndEnforcesBounds() {
        val salt = ByteArray(16) { (it + 5).toByte() }
        val encrypted = ByteArray(12 + 16) { (it * 5).toByte() }
        val data = EncryptedBackupHeader.frameArgon2id(65536, 3, 4, salt, encrypted)
        val header = EncryptedBackupHeader.parse(data)

        assertTrue(header.isArgon2id())
        assertEquals(EncryptedBackupHeader.Kdf.ARGON2ID, header.getKdf())
        assertEquals(65536, header.getMemoryKiB())
        assertEquals(3, header.getIterations())
        assertEquals(4, header.getParallelism())
        assertArrayEquals(salt, header.getSalt())
        assertArrayEquals(encrypted, header.getEncrypted())

        // Truncated Argon2id frame (between 44 and 57 bytes) must be rejected before derivation.
        try {
            EncryptedBackupHeader.parse(data.copyOfRange(0, EncryptedBackupHeader.MIN_ARGON2_BACKUP_BYTES - 1))
            throw AssertionError("truncated Argon2id backup accepted")
        } catch (_: IllegalArgumentException) { }

        // Unsupported version byte must be rejected.
        val badVersion = data.copyOf().also { it[4] = 99 }
        try {
            EncryptedBackupHeader.parse(badVersion)
            throw AssertionError("unknown Argon2id backup version accepted")
        } catch (_: IllegalArgumentException) { }

        // Out-of-bounds memory cost (e.g. 1 GiB) must be rejected before allocation.
        val hugeMem = data.copyOf().also { ByteBuffer.wrap(it, 5, 4).putInt(1_048_576) }
        try {
            EncryptedBackupHeader.parse(hugeMem)
            throw AssertionError("excessive Argon2id memory accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun nullInputIsRejected() {
        try {
            EncryptedBackupHeader.parse(null)
            throw AssertionError("null input accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
