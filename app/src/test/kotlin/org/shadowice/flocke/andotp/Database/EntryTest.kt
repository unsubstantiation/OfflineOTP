/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the entry model: token refresh across clock steps (including a backwards
 * clock), cached-token invalidation, destruction and equality. The URI serializer needs
 * android.net.Uri, so URI coverage lives in OtpUriParserTest and the instrumentation suite.
 */
package org.shadowice.flocke.andotp.Database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

import org.shadowice.flocke.andotp.Utilities.TokenCalculator

class EntryTest {
    private fun totp(): Entry = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Example",
        "alice", TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    private fun hotp(): Entry = Entry(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", 5L, 6, "Example",
        "alice", TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    @Test
    fun constructorStoresTheGivenFields() {
        val entry = totp()
        assertEquals(Entry.OTPType.TOTP, entry.getType())
        assertEquals("JBSWY3DPEHPK3PXP", entry.getSecretEncoded())
        assertEquals(30, entry.getPeriod())
        assertEquals(6, entry.getDigits())
        assertEquals("Example", entry.getIssuer())
        assertEquals("alice", entry.getLabel())
        assertEquals(TokenCalculator.HashAlgorithm.SHA1, entry.getAlgorithm())
        assertTrue(entry.isTimeBased())
        assertFalse(entry.isCounterBased())

        val counterEntry = hotp()
        assertFalse(counterEntry.isTimeBased())
        assertTrue(counterEntry.isCounterBased())
        assertEquals(5L, counterEntry.getCounter())
    }

    @Test
    fun constructorRejectsInvalidInput() {
        for (construct in listOf<() -> Entry>(
            { Entry(Entry.OTPType.TOTP, "", 30, 6, "i", "l", TokenCalculator.HashAlgorithm.SHA1, ArrayList()) },
            { Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 0, 6, "i", "l", TokenCalculator.HashAlgorithm.SHA1, ArrayList()) },
            { Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 10, "i", "l", TokenCalculator.HashAlgorithm.SHA1, ArrayList()) },
            { Entry(Entry.OTPType.STEAM, "JBSWY3DPEHPK3PXP", 30, 6, "i", "l", TokenCalculator.HashAlgorithm.SHA1, ArrayList()) },
            { Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "i", "l", TokenCalculator.HashAlgorithm.SHA1,
                ArrayList(listOf("t".repeat(200)))) },
            { Entry(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", -1L, 6, "i", "l", TokenCalculator.HashAlgorithm.SHA1, ArrayList()) }
        )) {
            try {
                construct()
                throw AssertionError("invalid entry accepted")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun timeBasedTokensRefreshOnStepBoundaries() {
        val entry = totp()
        // Steps of 30 s: t=60 is step 2, t=70 still step 2, t=90 is step 3.
        assertTrue(entry.updateOTPAt(60L, false))
        val stepTwo = entry.getCurrentOTP()
        assertEquals(6, stepTwo?.length)

        assertFalse(entry.updateOTPAt(70L, false))
        assertEquals(stepTwo, entry.getCurrentOTP())

        // One step ahead: the previous step's token becomes the previous token.
        assertTrue(entry.updateOTPAt(90L, false))
        assertEquals(stepTwo, entry.getPrevOTP())
        assertNotEquals(stepTwo, entry.getCurrentOTP())

        assertFalse(entry.updateOTPAt(95L, false))
        // updateNow forces a refresh even within the same step.
        assertTrue(entry.updateOTPAt(95L, true))
        assertEquals(6, entry.getCurrentOTP()?.length)
        // The forced refresh recomputed the previous step explicitly (offset -1 from t=95).
        assertEquals(stepTwo, entry.getPrevOTP())
    }

    @Test
    fun aBackwardsClockRefreshesInsteadOfShowingAFutureToken() {
        val entry = totp()
        assertTrue(entry.updateOTPAt(90L, false))  // step 3
        val future = entry.getCurrentOTP()

        // The clock jumps back into step 2; the visible token must be recomputed for that
        // step, not stay the future one.
        assertTrue(entry.updateOTPAt(65L, false))
        assertNotEquals(future, entry.getCurrentOTP())
        assertNotEquals(future, entry.getPrevOTP())

        // A large backwards jump (more than one step) recomputes both tokens from scratch.
        assertTrue(entry.updateOTPAt(0L, false))
        assertEquals(6, entry.getCurrentOTP()?.length)
        assertEquals(6, entry.getPrevOTP()?.length)
    }

    @Test
    fun counterBasedTokensFollowTheCounter() {
        val entry = hotp()
        assertTrue(entry.updateOTPAt(1700000000L, false))
        val first = entry.getCurrentOTP()
        assertEquals(6, first?.length)

        // A counter-based entry recomputes on every update.
        assertTrue(entry.updateOTPAt(1700000000L, false))
        entry.setCounter(1L)
        assertTrue(entry.updateOTPAt(1700000000L, false))
        assertNotEquals(first, entry.getCurrentOTP())
    }

    @Test
    fun editingTheSecretInvalidatesTheCachedToken() {
        val entry = totp()
        assertTrue(entry.updateOTPAt(90L, false))
        val before = entry.getCurrentOTP()

        entry.setSecret(EntryJson.decodeBase32Secret("JBSWY3DPEHPK3PXQ"))
        // Same step, no forced update: the changed secret must still refresh the token.
        assertTrue(entry.updateOTPAt(90L, false))
        assertNotEquals(before, entry.getCurrentOTP())
    }

    @Test
    fun editingTokenParametersInvalidatesTheCachedToken() {
        for (edit in listOf<(Entry) -> Unit>(
            { it.setPeriod(60) },
            { it.setDigits(8) },
            { it.setPin("1234") },
            { it.setType(Entry.OTPType.HOTP) }
        )) {
            val entry = totp()
            assertTrue(entry.updateOTPAt(90L, false))
            edit(entry)
            assertTrue(entry.updateOTPAt(90L, false))
        }
    }

    @Test
    fun editingNonTokenFieldsKeepsTheCachedToken() {
        val entry = totp()
        assertTrue(entry.updateOTPAt(90L, false))
        val token = entry.getCurrentOTP()

        // Labels, issuers, tags and bookkeeping do not change the token.
        entry.setLabel("bob")
        entry.setIssuer("Other", true)
        entry.setTags(ArrayList(listOf("work")))
        entry.setLastUsed(123L)
        entry.setUsedFrequency(5L)
        entry.setVisible(true)
        assertFalse(entry.updateOTPAt(95L, false))
        assertEquals(token, entry.getCurrentOTP())
    }

    @Test
    fun steamAndMotpTokensUpdate() {
        val steam = Entry(Entry.OTPType.STEAM, "JBSWY3DPEHPK3PXP", 30, 5, "Steam", "alice",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())
        assertTrue(steam.updateOTPAt(90L, false))
        assertEquals(5, steam.getCurrentOTP()?.length)

        val motp = Entry(Entry.OTPType.MOTP, "000102030405060708090a0b0c0d0e0f", "Example", "alice", ArrayList())
        motp.setPin("1234")
        assertTrue(motp.updateOTPAt(1700000000L, false))
        assertEquals(6, motp.getCurrentOTP()?.length)
        assertEquals(10, motp.getTokenStepSeconds())
    }

    @Test
    fun motpWithoutAPinShowsThePlaceholder() {
        val motp = Entry(Entry.OTPType.MOTP, "000102030405060708090a0b0c0d0e0f", "Example", "alice", ArrayList())
        assertTrue(motp.updateOTPAt(1700000000L, false))
        // The placeholder is displayed instead of a token and is not reused as a previous token.
        assertEquals("PINREQ", motp.getCurrentOTP())
        assertEquals("", motp.getPrevOTP())
    }

    @Test
    fun destroyedEntriesStopProducingTokens() {
        val entry = totp()
        entry.destroy()
        assertNull(entry.getSecret())
        assertFalse(entry.updateOTPAt(1700000000L, false))
        assertEquals("", entry.getPin())
        assertEquals("", entry.getLabel())
        try {
            entry.toJSON()
            throw AssertionError("destroyed entry still serializes")
        } catch (expected: NullPointerException) {
            // expected: destroyed entry has no seed
        }
    }

    @Test
    fun equalityIsContentBased() {
        assertEquals(totp(), totp())
        assertEquals(totp().hashCode(), totp().hashCode())

        val different = totp()
        different.setLabel("bob")
        assertNotEquals(totp(), different)

        // The runtime bookkeeping fields are not part of the content.
        val booked = totp()
        booked.setListId(99L)
        booked.setVisible(true)
        assertEquals(totp(), booked)
    }

    @Test
    fun jsonConstructorRoundTrips() {
        val original = totp()
        val restored = Entry(original.toJSON())
        assertEquals(original, restored)
        assertEquals(original.getSecretEncoded(), restored.getSecretEncoded())

        assertEquals(hotp(), Entry(hotp().toJSON()))
    }

    @Test
    fun jsonConstructorRejectsInvalidDocuments() {
        for (document in listOf(
            JSONObject(),
            JSONObject("""{"secret":""}"""),
            JSONObject("""{"secret":"!","label":"l"}"""),
            JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"l","type":"hotp"}"""),
            JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"l","period":0}""")
        )) {
            try {
                Entry(document)
                throw AssertionError("invalid document accepted: $document")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun uriConstructorParsesAndRejects() {
        assertEquals(totp(), Entry("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&issuer=Example"))

        for (invalid in listOf<String?>(
            null, "", "otpauth://totp/alice", "otpauth://hotp/alice?secret=JBSWY3DPEHPK3PXP"
        )) {
            try {
                Entry(invalid)
                throw AssertionError("invalid URI accepted: $invalid")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun legacyConstructorKeepsTheRawSeed() {
        // mOTP seeds are stored as raw UTF-8 ASCII hex bytes (16 bytes for a 16-character seed).
        val motp = Entry(Entry.OTPType.MOTP, "0001020304050607", "Example", "alice", ArrayList())
        assertEquals(16, motp.getSecret()?.size)
    }
}
