/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the entry document format: parse outcomes, the serialized field set and
 * byte-identical round trips. The Android platform's insertion-order behavior is covered by
 * EntryDocumentDeviceTest; the host org.json implementation intentionally does not promise it.
 */
package org.shadowice.flocke.andotp.Database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

import org.shadowice.flocke.andotp.Utilities.OtpHashAlgorithm
import org.shadowice.flocke.andotp.Utilities.OtpKind

class EntryJsonTest {
    private fun parse(json: JSONObject): EntryJsonResult = EntryJson.parse(json)

    private fun success(json: JSONObject, assertions: (ValidatedEntry) -> Unit) {
        val result = parse(json)
        assertTrue("expected success for $json, got $result", result is EntryJsonResult.Success)
        (result as EntryJsonResult.Success).value.use { assertions(it) }
    }

    private fun failure(json: JSONObject): EntryJsonError {
        val result = parse(json)
        assertTrue("expected failure for $json, got $result", result is EntryJsonResult.Failure)
        return (result as EntryJsonResult.Failure).error
    }

    private fun minimalDocument(): JSONObject =
        JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","issuer":"Example","label":"alice",
            "digits":6,"type":"TOTP","algorithm":"SHA1","thumbnail":"Default",
            "last_used":0,"used_frequency":0,"tags":[]}""")

    @Test
    fun privacyChoiceDefaultsHiddenAndOnlyBooleanTrueReveals() {
        success(minimalDocument()) { assertFalse(it.detailsVisible) }
        for (value in listOf(false, "true", 1, JSONObject.NULL)) {
            success(minimalDocument().put("details_visible", value)) { assertFalse(it.detailsVisible) }
        }
        success(minimalDocument().put("details_visible", true)) { assertTrue(it.detailsVisible) }
    }

    @Test
    fun privacyChoiceRoundTripsForEveryEntryKind() {
        val entries = listOf(
            Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Issuer", "OTP", org.shadowice.flocke.andotp.Utilities.TokenCalculator.HashAlgorithm.SHA1, arrayListOf()),
            Entry(Entry.OTPType.NOTE, "secret", "", "Note", arrayListOf()),
            Entry.sshKey("public", "private", "", "SSH", arrayListOf())
        )
        for (entry in entries) {
            assertFalse(entry.areDetailsVisible())
            assertFalse(entry.toJSON().has("details_visible"))
            assertFalse(Entry(entry.toJSON()).areDetailsVisible())
            entry.setDetailsVisible(true)
            assertTrue(Entry(entry.toJSON()).areDetailsVisible())
            entry.setDetailsVisible(false)
            assertFalse(Entry(entry.toJSON()).areDetailsVisible())
        }
    }

    @Test
    fun parsesAMinimalDocument() {
        success(minimalDocument()) { value ->
            assertEquals(OtpKind.TOTP, value.kind)
            assertEquals("alice", value.label)
            assertEquals("Example", value.issuer)
            assertEquals(30, value.period)
            assertEquals(6, value.digits)
            assertEquals(OtpHashAlgorithm.SHA1, value.algorithm)
            assertEquals("Default", value.thumbnailName)
            assertEquals(10, value.copySecret().size)
            assertEquals("", value.pin)
        }
    }

    @Test
    fun olderDocumentsDefaultTheMissingFields() {
        success(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"alice"}""")) { value ->
            assertEquals(OtpKind.TOTP, value.kind)
            assertEquals("", value.issuer)
            assertEquals(30, value.period)
            assertEquals(6, value.digits)
            assertEquals(OtpHashAlgorithm.SHA1, value.algorithm)
            assertEquals(null, value.thumbnailName)
        }
        success(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"alice","type":"STEAM"}""")) { value ->
            assertEquals(5, value.digits)
            assertEquals(30, value.period)
        }
        success(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"alice","type":"HOTP","counter":9}""")) { value ->
            assertEquals(9L, value.counter)
            assertEquals(30, value.period)
        }
        success(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"alice","type":"MOTP","pin":"1234"}""")) { value ->
            assertEquals("1234", value.pin)
        }
    }

    @Test
    fun motpIgnoresAStoredPeriodBecauseItNeverDrivesMotpTiming() {
        // mOTP always rolls over on TokenCalculator.MOTP_STEP_SECONDS (Entry.getTokenStepSeconds), never
        // on the stored period; serialize() never writes one and the otpauth:// parser forces 30, so a
        // "period" on an mOTP document can only come from a foreign/crafted file. The card shows the
        // actual 10-second step, never this foreign value.
        success(JSONObject(
            """{"secret":"JBSWY3DPEHPK3PXP","label":"alice","type":"MOTP","pin":"1234","period":60}""")
        ) { value ->
            assertEquals(30, value.period)
            assertEquals("1234", value.pin)
        }
    }

    @Test
    fun nonMotpDocumentsIgnoreAStoredPin() {
        // serialize() only ever writes "pin" for mOTP; a pin on another kind is foreign/crafted data
        // and must not be carried into the parsed entry (Entry.getPin() is otherwise only read for mOTP).
        success(JSONObject(
            """{"secret":"JBSWY3DPEHPK3PXP","label":"alice","type":"TOTP","pin":"1234"}""")
        ) { value -> assertEquals("", value.pin) }
        success(JSONObject(
            """{"secret":"JBSWY3DPEHPK3PXP","label":"alice","type":"HOTP","counter":0,"pin":"1234"}""")
        ) { value -> assertEquals("", value.pin) }
    }

    @Test
    fun tagsRoundTripInOrder() {
        val json = minimalDocument()
        json.put("tags", JSONArray(listOf("work", "personal")))
        success(json) { value -> assertEquals(listOf("work", "personal"), value.tags) }
    }

    @Test
    fun malformedDocumentsFailWithExplicitOutcomes() {
        assertEquals(EntryJsonError.MISSING_SECRET, failure(JSONObject()))
        assertEquals(EntryJsonError.MISSING_SECRET, failure(JSONObject("""{"label":"l"}""")))
        assertEquals(EntryJsonError.INVALID_SECRET, failure(JSONObject("""{"secret":"","label":"l"}""")))
        assertEquals(EntryJsonError.INVALID_SECRET, failure(JSONObject("""{"secret":"!!!","label":"l"}""")))
        assertEquals(EntryJsonError.INVALID_TEXT, failure(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP"}""")))
        assertEquals(EntryJsonError.INVALID_TEXT,
            failure(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":5}""")))
        assertEquals(EntryJsonError.INVALID_NUMBER,
            failure(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"l","period":"x"}""")))
        assertEquals(EntryJsonError.MISSING_COUNTER,
            failure(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"l","type":"HOTP"}""")))
        assertEquals(EntryJsonError.INVALID_PARAMETERS,
            failure(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"l","period":0}""")))
        assertEquals(EntryJsonError.INVALID_PARAMETERS,
            failure(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"l","type":"STEAM","digits":6}""")))
        assertEquals(EntryJsonError.UNSUPPORTED_TYPE,
            failure(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"l","type":"YUBIKEY"}""")))
        assertEquals(EntryJsonError.INVALID_ALGORITHM,
            failure(JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"l","algorithm":"MD5"}""")))
        assertEquals(EntryJsonError.TOO_MANY_TAGS, failure(JSONObject().put("secret", "JBSWY3DPEHPK3PXP")
            .put("label", "l").put("tags", JSONArray((1..33).map { "t$it" }))))
    }

    @Test
    fun textAndTagBoundsAreEnforced() {
        assertEquals(EntryJsonError.TEXT_TOO_LONG, failure(JSONObject().put("secret", "JBSWY3DPEHPK3PXP")
            .put("label", "l".repeat(1025))))
        assertEquals(EntryJsonError.TEXT_TOO_LONG, failure(JSONObject().put("secret", "JBSWY3DPEHPK3PXP")
            .put("label", "l").put("issuer", "i".repeat(1025))))
        assertEquals(EntryJsonError.TOO_MANY_TAGS, failure(JSONObject().put("secret", "JBSWY3DPEHPK3PXP")
            .put("label", "l").put("tags", JSONArray((1..33).map { "t" }))))
    }

    @Test
    fun serializeWritesTheStoredFieldSet() {
        val secret = ByteArray(10) { it.toByte() }
        val encoded = String(org.apache.commons.codec.binary.Base32().encode(secret))
        val json = EntryJson.serialize(
            kind = OtpKind.TOTP, secret = secret, issuer = "Example",
            label = "alice", digits = 6, period = 30, counter = 0, algorithm = OtpHashAlgorithm.SHA1,
            thumbnailName = "Default", lastUsed = 5, usedFrequency = 7,
            tags = listOf("work"), pin = "")
        // org.json:json (the real host implementation) stores keys in a HashMap, so a JVM test
        // cannot assert Android JSONObject's insertion-order rendering. Check the canonical
        // field set and values here; ApplicationTest.testEntry asserts the byte-exact Android
        // representation on the platform implementation.
        assertEquals(setOf(
            "secret", "issuer", "label", "digits", "type", "algorithm", "thumbnail",
            "last_used", "used_frequency", "period", "tags"
        ), json.keys().asSequence().toSet())
        assertEquals(encoded, json.getString("secret"))
        assertEquals("Example", json.getString("issuer"))
        assertEquals("alice", json.getString("label"))
        assertEquals(6, json.getInt("digits"))
        assertEquals("TOTP", json.getString("type"))
        assertEquals("SHA1", json.getString("algorithm"))
        assertEquals("Default", json.getString("thumbnail"))
        assertEquals(5L, json.getLong("last_used"))
        assertEquals(7L, json.getLong("used_frequency"))
        assertEquals(30, json.getInt("period"))
        assertEquals(listOf("work"), json.getJSONArray("tags").let { array ->
            (0 until array.length()).map { array.getString(it) }
        })
    }

    @Test
    fun serializeOmitsOptionalFieldsForNonDefaultKinds() {
        val hotp = EntryJson.serialize(OtpKind.HOTP, ByteArray(10), "i", "l", 6, 30, 42,
            OtpHashAlgorithm.SHA1, "Default", 0, 0, emptyList(), "")
        assertTrue(hotp.has("counter"))
        assertFalse(hotp.has("period"))
        assertFalse(hotp.has("pin"))

        val steamDefault = EntryJson.serialize(OtpKind.STEAM, ByteArray(10), "i", "l", 5, 30, 0,
            OtpHashAlgorithm.SHA1, "Default", 0, 0, emptyList(), "")
        assertFalse(steamDefault.has("period"))

        val steamCustom = EntryJson.serialize(OtpKind.STEAM, ByteArray(10), "i", "l", 5, 60, 0,
            OtpHashAlgorithm.SHA1, "Default", 0, 0, emptyList(), "")
        assertEquals(60, steamCustom.getInt("period"))

        val motp = EntryJson.serialize(OtpKind.MOTP, ByteArray(10), "i", "l", 6, 30, 0,
            OtpHashAlgorithm.SHA1, "Default", 0, 0, emptyList(), "1234")
        assertEquals("1234", motp.getString("pin"))
        assertFalse(motp.has("period"))
        assertFalse(motp.has("counter"))
    }

    @Test
    fun roundTripIsByteIdentical() {
        // A document in the writer's canonical field order must come back byte-identical.
        val original = JSONObject()
            .put("secret", "JBSWY3DPEHPK3PXP")
            .put("issuer", "Example")
            .put("label", "alice")
            .put("digits", 6)
            .put("type", "TOTP")
            .put("algorithm", "SHA1")
            .put("thumbnail", "Default")
            .put("last_used", 0)
            .put("used_frequency", 0)
            .put("period", 30)
            .put("tags", JSONArray(listOf("work", "personal")))
        val result = EntryJson.parse(original)
        assertTrue(result is EntryJsonResult.Success)
        (result as EntryJsonResult.Success).value.use { value ->
            val reserialized = EntryJson.serialize(value.kind, value.copySecret(), value.issuer,
                value.label, value.digits, value.period, value.counter, value.algorithm,
                value.thumbnailName ?: "Default", value.lastUsed, value.usedFrequency, value.tags, value.pin)
            assertEquals(original.toString(), reserialized.toString())
        }
    }
}
