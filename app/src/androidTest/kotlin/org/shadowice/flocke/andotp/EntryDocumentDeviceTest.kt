/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Device-only document vectors for the platform org.json parser. The URI serializer
 * (Entry.toUriString) is now Android-free and only assembles a percent-encoded string, but the
 * stored document is parsed by the platform JSON parser, so the JSON behaviour stays asserted here;
 * the URI round trips below guard the string serializer against regressions on a real device.
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
    fun sshKeyPairRoundTripsThroughAndroidJson() {
        val privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "x".repeat(5000) + "\n-----END OPENSSH PRIVATE KEY-----\n"
        val original = Entry.sshKey("ssh-ed25519 public\n", privateKey, "", "Server", arrayListOf())
        val restored = Entry(original.toJSON())
        assertEquals(Entry.OTPType.SSH_KEY, restored.getType())
        assertEquals("ssh-ed25519 public\n", restored.getSshKeys().publicKey)
        assertEquals(privateKey, restored.getSshKeys().privateKey)
        assertTrue(!restored.updateOTP(false))
    }

    @Test
    fun secureNoteRoundTripsThroughAndroidJson() {
        val original = Entry(Entry.OTPType.NOTE, "private key\nwith spaces 🔐", "", "Recovery", arrayListOf())
        val restored = Entry(original.toJSON())
        assertEquals(Entry.OTPType.NOTE, restored.getType())
        assertEquals("private key\nwith spaces 🔐", restored.getNote())
        assertTrue(!restored.updateOTP(false))
    }

    @Test
    fun totpUriRoundTripsThroughTheUriStringSerializer() {
        val original = totp()
        val uri = original.toUriString()
        assertNotNull(uri)
        val restored = Entry(uri)
        assertEquals(original, restored)
        assertEquals(30, restored.getPeriod())
        assertEquals(6, restored.getDigits())
    }

    @Test
    fun uriCarriesNonDefaultParametersAndTags() {
        val original = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 60, 8, "Example",
            "alice", TokenCalculator.HashAlgorithm.SHA256, ArrayList(listOf("work", "cloud")))
        val restored = Entry(original.toUriString())
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
        val restoredHotp = Entry(hotp.toUriString())
        assertEquals(hotp, restoredHotp)
        assertEquals(7L, restoredHotp.getCounter())

        val steam = Entry(Entry.OTPType.STEAM, "JBSWY3DPEHPK3PXP", 30, 5, "Steam", "alice",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())
        val restoredSteam = Entry(steam.toUriString())
        assertEquals(steam, restoredSteam)
        assertEquals(5, restoredSteam.getDigits())
    }

    @Test
    fun motpUriRoundTripKeepsTheSeedButNotThePin() {
        val motp = Entry(Entry.OTPType.MOTP, "000102030405060708090a0b0c0d0e0f", "Example",
            "alice", ArrayList())
        motp.setPin("1234")
        val restored = Entry(motp.toUriString())
        // The URI format has no PIN parameter: the seed survives, the PIN does not.
        assertEquals(motp.getSecretEncoded(), restored.getSecretEncoded())
        assertEquals("", restored.getPin())
    }

    @Test
    fun labelsWithSpacesAndColonsSurviveTheRoundTrip() {
        val original = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "ACME Co",
            "alice@example.com", TokenCalculator.HashAlgorithm.SHA1, ArrayList())
        val restored = Entry(original.toUriString())
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
            entry.toUriString()
            throw AssertionError("destroyed entry still builds a URI")
        } catch (expected: NullPointerException) {
            // expected: destroyed entry has no seed
        }
        assertTrue(entry.getLabel().isEmpty())
    }
}
