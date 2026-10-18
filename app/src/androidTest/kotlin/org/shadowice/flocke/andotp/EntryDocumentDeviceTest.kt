/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Device-only document vectors: android.net.Uri and the platform org.json. The URI serializer
 * (Entry.toUri) is built on the platform Uri builder, and the stored document is parsed by the
 * platform JSON parser, so their behaviour is asserted here rather than in the host suite.
 */
package org.shadowice.flocke.andotp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

@RunWith(AndroidJUnit4::class)
class EntryDocumentDeviceTest {
    private fun totp(): Entry = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Example",
        "alice", TokenCalculator.HashAlgorithm.SHA1, ArrayList(listOf("work")))

    @Test
    fun totpUriRoundTripsThroughThePlatformUriBuilder() {
        val original = totp()
        val uri = original.toUri()
        assertNotNull(uri)
        val restored = Entry(uri.toString())
        assertEquals(original, restored)
        assertEquals(30, restored.getPeriod())
        assertEquals(6, restored.getDigits())
    }

    @Test
    fun uriCarriesNonDefaultParametersAndTags() {
        val original = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 60, 8, "Example",
            "alice", TokenCalculator.HashAlgorithm.SHA256, ArrayList(listOf("work", "cloud")))
        val restored = Entry(original.toUri().toString())
        assertEquals(original, restored)
        assertEquals(60, restored.getPeriod())
        assertEquals(8, restored.getDigits())
        assertEquals(TokenCalculator.HashAlgorithm.SHA256, restored.getAlgorithm())
        assertEquals(listOf("work", "cloud"), restored.getTags())
    }

    @Test
    fun hotpAndSteamEntriesRoundTrip() {
        val hotp = Entry(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", 7L, 6, "Example", "alice",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())
        val restoredHotp = Entry(hotp.toUri().toString())
        assertEquals(hotp, restoredHotp)
        assertEquals(7L, restoredHotp.getCounter())

        val steam = Entry(Entry.OTPType.STEAM, "JBSWY3DPEHPK3PXP", 30, 5, "Steam", "alice",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())
        val restoredSteam = Entry(steam.toUri().toString())
        assertEquals(steam, restoredSteam)
        assertEquals(5, restoredSteam.getDigits())
    }

    @Test
    fun motpUriRoundTripKeepsTheSeedButNotThePin() {
        val motp = Entry(Entry.OTPType.MOTP, "000102030405060708090a0b0c0d0e0f", "Example",
            "alice", ArrayList())
        motp.setPin("1234")
        val restored = Entry(motp.toUri().toString())
        // The URI format has no PIN parameter: the seed survives, the PIN does not.
        assertEquals(motp.getSecretEncoded(), restored.getSecretEncoded())
        assertEquals("", restored.getPin())
    }

    @Test
    fun labelsWithSpacesAndColonsSurviveTheRoundTrip() {
        val original = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "ACME Co",
            "alice@example.com", TokenCalculator.HashAlgorithm.SHA1, ArrayList())
        val restored = Entry(original.toUri().toString())
        assertEquals(original, restored)
        assertEquals("alice@example.com", restored.getLabel())
        assertEquals("ACME Co", restored.getIssuer())
    }

    @Test
    fun platformJsonParsesAndReserializesTheStoredDocument() {
        val original = totp()
        val restored = Entry(original.toJSON())
        assertEquals(original, restored)
        // The platform org.json preserves the writer's field order, so the document is stable.
        assertEquals(original.toJSON().toString(), restored.toJSON().toString())
    }

    @Test
    fun destroyedEntriesDoNotSerialize() {
        val entry = totp()
        entry.destroy()
        assertNull(entry.getSecret())
        try {
            entry.toUri()
            throw AssertionError("destroyed entry still builds a URI")
        } catch (expected: NullPointerException) {
            // expected: destroyed entry has no seed
        }
        assertTrue(entry.getLabel().isEmpty())
    }
}
