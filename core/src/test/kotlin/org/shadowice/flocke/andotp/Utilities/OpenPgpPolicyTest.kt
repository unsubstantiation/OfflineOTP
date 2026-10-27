package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenPgpPolicyTest {
    @Test
    fun providerMustBeConfiguredAndBoundBeforeEitherActionCanRun() {
        assertFalse(OpenPgpAvailability.evaluate(false, false, 1).canRestore)
        assertFalse(OpenPgpAvailability.evaluate(true, false, 1).canEncrypt)
        assertFalse(OpenPgpAvailability.evaluate(true, false, 1).canRestore)
        assertTrue(OpenPgpAvailability.evaluate(true, true, 1).canRestore)
        assertTrue(OpenPgpAvailability.evaluate(true, true, 1).canEncrypt)
    }

    @Test
    fun encryptionNeedsRecipientsButRestoreDoesNot() {
        val ready = OpenPgpAvailability.evaluate(providerConfigured = true, serviceReady = true, recipientCount = 0)
        assertTrue(ready.canRestore)
        assertFalse(ready.canEncrypt)
    }

    @Test
    fun emptyProviderOutputIsNotAValidEncryptedBackup() {
        assertFalse(OpenPgpOutputPolicy.hasContent(null))
        assertFalse(OpenPgpOutputPolicy.hasContent(""))
        assertFalse(OpenPgpOutputPolicy.hasContent(" \n\t"))
        assertTrue(OpenPgpOutputPolicy.hasContent("-----BEGIN PGP MESSAGE-----\n"))
    }
}
