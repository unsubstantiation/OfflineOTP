/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the file-backed hardware-unlock record: real FIDO2-PRF envelope sealing
 * (the shared core HardwareUnlockEnvelope), bounded Base64 persistence, validation on read and
 * record removal.
 */
package org.shadowice.flocke.andotp.desktop.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.HardwareUnlockEnvelope
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

class DesktopHardwareUnlockStoreTest {

    private val random = SecureRandom()

    private fun newEnvelope(vaultKey: ByteArray = ByteArray(16) { it.toByte() }): ByteArray {
        val credentialId = "credential-1234".toByteArray()
        val salt = ByteArray(HardwareUnlockEnvelope.SALT_BYTES).also(random::nextBytes)
        val prf = ByteArray(HardwareUnlockEnvelope.PRF_BYTES).also(random::nextBytes)
        return HardwareUnlockEnvelope.seal(credentialId, salt, prf, vaultKey, random)
    }

    @Test
    fun anEnvelopeRoundTripsAndStaysRecoverable() {
        val dir = Files.createTempDirectory("offlineotp-hw-").toFile()
        val store = DesktopHardwareUnlockStore(dir)
        assertNull(store.readEnvelope())

        val envelope = newEnvelope()
        assertTrue(store.writeEnvelope(envelope))
        assertArrayEquals(envelope, store.readEnvelope())

        // A fresh instance reads the same persisted record.
        assertArrayEquals(envelope, DesktopHardwareUnlockStore(dir).readEnvelope())
    }

    @Test
    fun garbageAndOversizedRecordsAreRejected() {
        val dir = Files.createTempDirectory("offlineotp-hw-").toFile()
        val store = DesktopHardwareUnlockStore(dir)

        assertEquals(false, store.writeEnvelope("not an envelope".toByteArray()))
        File(dir, "hardware-unlock.b64").writeText("not-base64-envelope")
        assertNull(store.readEnvelope())

        File(dir, "hardware-unlock.b64").writeText(java.util.Base64.getEncoder()
            .encodeToString(ByteArray(4096) { 1 }))
        assertNull(store.readEnvelope())
    }

    @Test
    fun removalLeavesNoRecord() {
        val dir = Files.createTempDirectory("offlineotp-hw-").toFile()
        val store = DesktopHardwareUnlockStore(dir)
        store.writeEnvelope(newEnvelope())
        assertTrue(store.readEnvelope() != null)

        store.removeEnvelope()
        assertNull(store.readEnvelope())
        // Removing a missing record is a no-op rather than an error.
        store.removeEnvelope()
    }

    @Test
    fun theStoredEnvelopeStillOpensWithItsPrf() {
        val dir = Files.createTempDirectory("offlineotp-hw-").toFile()
        val store = DesktopHardwareUnlockStore(dir)
        val vaultKey = ByteArray(32) { (it * 3).toByte() }
        val credentialId = "credential".toByteArray()
        val salt = ByteArray(HardwareUnlockEnvelope.SALT_BYTES).also(random::nextBytes)
        val prf = ByteArray(HardwareUnlockEnvelope.PRF_BYTES).also(random::nextBytes)
        val envelope = HardwareUnlockEnvelope.seal(credentialId, salt, prf, vaultKey, random)

        store.writeEnvelope(envelope)
        val recovered = HardwareUnlockEnvelope.open(store.readEnvelope()!!, prf)
        assertArrayEquals(vaultKey, recovered)
    }
}
