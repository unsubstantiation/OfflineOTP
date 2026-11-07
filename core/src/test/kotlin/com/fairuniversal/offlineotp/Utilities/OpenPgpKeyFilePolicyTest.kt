/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the in-app OpenPGP key-file mode: mode parsing must never silently switch an
 * existing provider install, only bounded content URIs may become key documents, and neither action
 * is offered while the native backend is missing.
 */
package com.fairuniversal.offlineotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenPgpKeyFilePolicyTest {

    private val alice = "content://com.android.providers.downloads.documents/document/1"
    private val bob = "content://com.android.providers.downloads.documents/document/2"

    @Test
    fun absentOrUnknownModeKeepsTheConfiguredProvider() {
        assertEquals(OpenPgpMode.PROVIDER, OpenPgpMode.parse(null))
        assertEquals(OpenPgpMode.PROVIDER, OpenPgpMode.parse(""))
        assertEquals(OpenPgpMode.PROVIDER, OpenPgpMode.parse("provider"))
        assertEquals(OpenPgpMode.PROVIDER, OpenPgpMode.parse("KEY_FILE"))
        assertEquals(OpenPgpMode.PROVIDER, OpenPgpMode.parse("something-else"))
        assertEquals(OpenPgpMode.KEY_FILE, OpenPgpMode.parse("key_file"))
    }

    @Test
    fun modeRoundTripsThroughItsPreferenceValue() {
        for (mode in OpenPgpMode.values()) {
            assertEquals(mode, OpenPgpMode.parse(OpenPgpMode.preferenceValue(mode)))
        }
    }

    @Test
    fun onlyBoundedContentUrisAreUsableKeyDocuments() {
        assertTrue(OpenPgpKeyFilePolicy.isUsableKeyUri(alice))
        assertFalse(OpenPgpKeyFilePolicy.isUsableKeyUri(null))
        assertFalse(OpenPgpKeyFilePolicy.isUsableKeyUri(""))
        assertFalse(OpenPgpKeyFilePolicy.isUsableKeyUri("content://"))
        // A raw path must never be read, even if it names a real key file.
        assertFalse(OpenPgpKeyFilePolicy.isUsableKeyUri("/sdcard/secret.asc"))
        assertFalse(OpenPgpKeyFilePolicy.isUsableKeyUri("file:///sdcard/secret.asc"))
        assertFalse(OpenPgpKeyFilePolicy.isUsableKeyUri("https://example.test/key.asc"))
        assertFalse(OpenPgpKeyFilePolicy.isUsableKeyUri(
            "content://provider/" + "x".repeat(InputPolicy.MAX_URI_CHARS)))
    }

    @Test
    fun recipientsAreFilteredDeduplicatedAndStablyOrdered() {
        val recipients = OpenPgpKeyFilePolicy.recipients(setOf(bob, alice, bob, "/sdcard/key.asc", ""))
        assertEquals(listOf(alice, bob), recipients)
        // Preference-set iteration order must not change the recipient set of a backup.
        assertEquals(recipients, OpenPgpKeyFilePolicy.recipients(setOf("", bob, "/x", alice)))
        assertEquals(emptyList<String>(), OpenPgpKeyFilePolicy.recipients(emptySet()))
    }

    @Test
    fun recipientCountIsCappedAtTheBackendCeiling() {
        val many = (1..OpenPgpKeyFilePolicy.MAX_RECIPIENTS + 10)
            .map { "content://provider/document/$it" }.toSet()
        assertEquals(OpenPgpKeyFilePolicy.MAX_RECIPIENTS, OpenPgpKeyFilePolicy.recipients(many).size)
    }

    @Test
    fun aMissingBackendDisablesBothActions() {
        val offline = OpenPgpKeyFilePolicy.availability(
            backendAvailable = false, recipientCount = 2, secretKeyConfigured = true)
        assertFalse(offline.canEncrypt)
        assertFalse(offline.canRestore)
    }

    @Test
    fun encryptNeedsRecipientsAndRestoreNeedsASecretKey() {
        val noKeys = OpenPgpKeyFilePolicy.availability(true, 0, false)
        assertFalse(noKeys.canEncrypt)
        assertFalse(noKeys.canRestore)

        val recipientsOnly = OpenPgpKeyFilePolicy.availability(true, 1, false)
        assertTrue(recipientsOnly.canEncrypt)
        assertFalse(recipientsOnly.canRestore)

        val secretOnly = OpenPgpKeyFilePolicy.availability(true, 0, true)
        assertFalse(secretOnly.canEncrypt)
        assertTrue(secretOnly.canRestore)

        val both = OpenPgpKeyFilePolicy.availability(true, 1, true)
        assertTrue(both.canEncrypt)
        assertTrue(both.canRestore)
    }

    @Test
    fun anOutOfRangeRecipientCountNeverEnablesEncryption() {
        assertFalse(OpenPgpKeyFilePolicy.availability(true, -1, true).canEncrypt)
        assertFalse(OpenPgpKeyFilePolicy.availability(
            true, OpenPgpKeyFilePolicy.MAX_RECIPIENTS + 1, true).canEncrypt)
        assertTrue(OpenPgpKeyFilePolicy.availability(
            true, OpenPgpKeyFilePolicy.MAX_RECIPIENTS, true).canEncrypt)
    }
}
