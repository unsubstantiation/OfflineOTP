package org.shadowice.flocke.andotp.Database

import org.apache.commons.codec.binary.Base32
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupCodec
import org.shadowice.flocke.andotp.Utilities.InputPolicy

class SecureNoteTest {
    private fun note(text: String) = Entry(Entry.OTPType.NOTE, text, "", "Private key", arrayListOf("private"))

    @Test fun noteRoundTripsThroughVaultAndEncryptedBackupWithoutOtpBehavior() {
        val text = "key with spaces\nand unicode 🔐"
        val original = note(text)
        val document = VaultJson.entriesToString(listOf(original))
        val blob = EncryptedBackupCodec.seal("password", document)
        val restored = VaultJson.stringToEntries(EncryptedBackupCodec.open("password", blob, false)).single()
        assertEquals(Entry.OTPType.NOTE, restored.getType())
        assertEquals(text, restored.getNote())
        assertEquals(listOf("private"), restored.getTags())
        assertFalse(restored.isTimeBased())
        assertFalse(restored.isCounterBased())
        assertFalse(restored.updateOTP(false))
        assertFalse(restored.hasCopyableToken())
        try { restored.toUriString(); throw AssertionError("note exported as OTP") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun noteLengthIsCountedInUnicodeCharactersAndInvalidTextIsRejected() {
        assertEquals("🔐".repeat(200), note("🔐".repeat(200)).getNote())
        for (text in listOf("", "a".repeat(201), "🔐".repeat(201), "\uD800")) {
            try { note(text); throw AssertionError("invalid note accepted") }
            catch (_: IllegalArgumentException) { }
        }
        try { Entry(Entry.OTPType.NOTE, "value", "", " ", arrayListOf()); throw AssertionError("blank title") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun malformedOrOversizedNoteDocumentsFailClosed() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), "x".repeat(201).toByteArray())) {
            val json = JSONObject().put("type", "NOTE").put("label", "Title")
                .put("secret", Base32().encodeAsString(bytes))
            assertTrue(EntryJson.parse(json) is EntryJsonResult.Failure)
        }
        assertEquals(200, InputPolicy.MAX_NOTE_CHARACTERS)
    }

    @Test fun failedNoteEditRestoresOriginalText() {
        val value = note("original")
        try {
            value.applyEdit { it.setNote("replacement"); it.setLabel("") }
            throw AssertionError("invalid edit accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals("original", value.getNote())
        assertEquals("Private key", value.getLabel())
    }
}
