package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

class PortableVaultProtocolTest {
    private val policy = VaultProtocol()
    private val salt = ByteArray(16) { it.toByte() }
    @Test fun vaultAndBackupDerivationsRemainDeliberatelyDifferent() {
        val vault = policy.makeVaultHeader("argon2id", salt, 32, 1, 1).header!!
        val backup = policy.makeBackupHeader(salt, 32, 1, 1).header!!
        assertEquals(64, vault.derivedBytes); assertEquals(32, vault.keyBytes)
        assertEquals(32, backup.derivedBytes); assertEquals(32, backup.keyBytes)
        val legacy = policy.makeVaultHeader("pbkdf2", salt, -1, 1, -1).header!!
        assertEquals(32, legacy.derivedBytes); assertEquals(16, legacy.keyBytes)
        assertEquals(0, legacy.memoryKiB); assertEquals(0, legacy.parallelism)
    }
    @Test fun parsedPublicMetadataCannotBeAliased() {
        val input = salt.copyOf()
        val made = policy.makeVaultHeader("argon2id", input, 32, 1, 1).header!!
        input.fill(99); made.salt().fill(99); made.bytes().fill(99)
        assertTrue(salt.contentEquals(made.salt()))
        val framed = made.bytes() + ByteArray(30)
        val read = policy.vaultHeader(framed, framed.size).header!!
        framed.fill(99)
        assertTrue(made.bytes().contentEquals(read.bytes()))
        assertEquals("ProtocolHeader(redacted)", read.toString())
    }
    @Test fun malformedHeadersAreRejectedWithoutDerivingOrAllocatingTheirRequestedWork() {
        for (memory in listOf(Int.MIN_VALUE, -1, 0, 7, 262145, Int.MAX_VALUE)) {
            assertNull(policy.makeVaultHeader("argon2id", salt, memory, 1, 1).header)
            assertNull(policy.makeBackupHeader(salt, memory, 1, 1).header)
        }
        assertNull(policy.makeBackupHeader(salt, 262144, 5, 1).header)
        assertNotNull(policy.makeBackupHeader(salt, 262144, 4, 1).header)
        assertNull(policy.makeVaultHeader("argon2id", salt, 32, 1, Int.MAX_VALUE).header)
        val header = policy.makeVaultHeader("argon2id", salt, 32, 1, 1).header!!.bytes()
        for (size in 0 until 40) assertNull(policy.vaultHeader(header.copyOf(size), 70).header)
        header[8] = 127
        assertTrue(policy.isVault(header)); assertNull(policy.vaultHeader(header, 70).header)
        assertEquals(BackupRoute.VAULT_COPY, policy.select(header, 70, "auto", "").route)
        assertNull(policy.select(header, 70, "offlineotp", "").route)
        assertNull(policy.select(header, 70, "andotp", "pre063").route)
    }
    @Test fun deterministicArbitraryHeadersNeverThrowOrRequestUnboundedResources() {
        var state = 1234567
        repeat(5000) {
            val input = ByteArray(it % 65) { state = state * 1664525 + 1013904223; (state ushr 24).toByte() }
            val size = if (it % 3 == 0) Int.MAX_VALUE else it
            val results = listOf(policy.vaultHeader(input, size), policy.backupHeader(input, size))
            results.forEach { result ->
                assertTrue((result.header == null) != (result.failure == null))
                result.header?.let { assertTrue(it.iterations in 1..KdfPolicy.MAX_VAULT_PBKDF2) }
            }
            listOf("auto", "vault-copy", "offlineotp", "andotp", "bad").forEach { format ->
                val result = policy.select(input, size, format, "encrypted")
                assertTrue((result.route == null) != (result.failure == null))
            }
        }
    }
}
