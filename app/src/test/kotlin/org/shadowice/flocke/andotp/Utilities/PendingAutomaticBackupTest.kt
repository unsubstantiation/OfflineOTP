package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/**
 * Coverage of [PendingAutomaticBackup.fingerprint], which stays in the app module because the
 * pending-backup marker store is Android-backed. This previously lived colocated in
 * LoginBackupKeyTest; it moved here when LoginBackupKey and the session classes went to :core.
 */
class PendingAutomaticBackupTest {
    @Test
    fun pendingFingerprintsAreKeyedAndDoNotModifyTheVaultKey() {
        val key = SecretKeySpec(ByteArray(16) { 42 }, "AES")
        val other = SecretKeySpec(ByteArray(16) { 43 }, "AES")
        val before = key.encoded
        val first = PendingAutomaticBackup.fingerprint("[]", key)
        assertNotNull(first)
        assertEquals(first, PendingAutomaticBackup.fingerprint("[]", key))
        assertFalse(first == PendingAutomaticBackup.fingerprint("[{\"x\":1}]", key))
        assertFalse(first == PendingAutomaticBackup.fingerprint("[]", other))
        assertArrayEquals(before, key.encoded)
        val aliased = ByteArray(16) { 9 }
        val providerKey = object : javax.crypto.SecretKey {
            override fun getAlgorithm(): String = "AES"
            override fun getFormat(): String = "RAW"
            override fun getEncoded(): ByteArray = aliased
        }
        assertNotNull(PendingAutomaticBackup.fingerprint("[]", providerKey))
        assertArrayEquals(ByteArray(16) { 9 }, aliased)
    }
}
