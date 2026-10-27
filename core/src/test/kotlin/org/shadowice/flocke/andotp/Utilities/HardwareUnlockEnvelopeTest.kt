package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareUnlockEnvelopeTest {
    private val id = ByteArray(64) { (it + 1).toByte() }
    private val salt = ByteArray(HardwareUnlockEnvelope.SALT_BYTES) { (it + 2).toByte() }
    private val prf = ByteArray(HardwareUnlockEnvelope.PRF_BYTES) { (it + 3).toByte() }

    @Test
    fun wrapsAndRecoversOnlyWithTheRightHardwareOutput() {
        for (length in listOf(16, 24, 32)) {
            // Keep this fixture distinct from the public credential ID and salt.
            val key = ByteArray(length) { (0x80 + it).toByte() }
            val envelope = HardwareUnlockEnvelope.seal(id, salt, prf, key)
            val request = HardwareUnlockEnvelope.request(envelope)!!
            assertArrayEquals(id, request.credentialId)
            assertArrayEquals(salt, request.salt)
            assertArrayEquals(key, HardwareUnlockEnvelope.open(envelope, prf))
            val wrong = prf.clone().also { it[0] = (it[0] + 1).toByte() }
            assertNull(HardwareUnlockEnvelope.open(envelope, wrong))
            assertFalse(envelope.toList().windowed(key.size).any { it == key.toList() })
            key.fill(0)
        }
    }

    @Test
    fun everyHeaderAndCiphertextByteIsAuthenticated() {
        val envelope = HardwareUnlockEnvelope.seal(id, salt, prf, ByteArray(16) { 0x42 })
        for (i in envelope.indices) {
            val edited = envelope.clone().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("edited byte $i", HardwareUnlockEnvelope.open(edited, prf))
        }
    }

    @Test
    fun validatesBoundsAndRejectsTruncationWithoutLeakingRequestData() {
        val envelope = HardwareUnlockEnvelope.seal(id, salt, prf, ByteArray(32))
        for (i in 0 until envelope.size) assertNull(HardwareUnlockEnvelope.open(envelope.copyOf(i), prf))
        assertNull(HardwareUnlockEnvelope.request(ByteArray(3000)))
        assertNull(HardwareUnlockEnvelope.open(envelope, ByteArray(31)))
        assertThrows(IllegalArgumentException::class.java) {
            HardwareUnlockEnvelope.seal(ByteArray(0), salt, prf, ByteArray(16))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardwareUnlockEnvelope.seal(ByteArray(1025), salt, prf, ByteArray(16))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardwareUnlockEnvelope.seal(id, salt, prf, ByteArray(15))
        }
        val text = HardwareUnlockEnvelope.request(envelope).toString()
        assertTrue(text.contains("redacted"))
        assertFalse(text.contains(id.contentToString()))
    }

    @Test
    fun repeatedWrapsUseFreshNonces() {
        val key = ByteArray(16) { 5 }
        val first = HardwareUnlockEnvelope.seal(id, salt, prf, key)
        val second = HardwareUnlockEnvelope.seal(id, salt, prf, key)
        assertNotEquals(first.toList(), second.toList())
        assertArrayEquals(key, HardwareUnlockEnvelope.open(first, prf))
        assertArrayEquals(key, HardwareUnlockEnvelope.open(second, prf))
    }
}
