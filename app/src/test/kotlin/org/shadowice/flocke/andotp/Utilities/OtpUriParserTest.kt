/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the otpauth:// boundary: every accepted shape and the explicit failure
 * outcomes for malformed input.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpUriParserTest {
    private fun parse(input: String?): OtpParseResult = OtpUriParser.parse(input)

    private fun success(input: String, assertions: (ValidatedOtp) -> Unit) {
        val result = parse(input)
        assertTrue("expected success for $input, got $result", result is OtpParseResult.Success)
        (result as OtpParseResult.Success).value.use { assertions(it) }
    }

    private fun failure(input: String?): OtpParseError {
        val result = parse(input)
        assertTrue("expected failure for $input, got $result", result is OtpParseResult.Failure)
        return (result as OtpParseResult.Failure).error
    }

    @Test
    fun parsesATotpUriWithDefaults() {
        success("otpauth://totp/ACME%20Co:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co") { value ->
            assertEquals(OtpKind.TOTP, value.kind)
            assertEquals("alice@example.com", value.label)
            assertEquals("ACME Co", value.issuer)
            assertEquals(30, value.period)
            assertEquals(6, value.digits)
            assertEquals(OtpHashAlgorithm.SHA1, value.algorithm)
        }
    }

    @Test
    fun parsesExplicitParameters() {
        success("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&period=60&digits=8&algorithm=SHA256"
            + "&issuer=Example&tags=a&tags=b") { value ->
            assertEquals(60, value.period)
            assertEquals(8, value.digits)
            assertEquals(OtpHashAlgorithm.SHA256, value.algorithm)
            assertEquals(listOf("a", "b"), value.tags)
        }
    }

    @Test
    fun parsesHotpWithCounter() {
        success("otpauth://hotp/alice?secret=JBSWY3DPEHPK3PXP&counter=42&issuer=Example") { value ->
            assertEquals(OtpKind.HOTP, value.kind)
            assertEquals(42L, value.counter)
            assertEquals(30, value.period)  // the stored period is 30 for counter-based kinds
        }
    }

    @Test
    fun parsesSteamWithFiveDigits() {
        success("otpauth://steam/alice?secret=JBSWY3DPEHPK3PXP") { value ->
            assertEquals(OtpKind.STEAM, value.kind)
            assertEquals(5, value.digits)
        }
        success("otpauth://steam/alice?secret=JBSWY3DPEHPK3PXP&period=60") { value ->
            assertEquals(60, value.period)
        }
    }

    @Test
    fun parsesMotpWithHexSecret() {
        // The mOTP seed is stored as the ASCII hex text, not decoded nibbles.
        success("otpauth://motp/alice?secret=0001020304050607&issuer=Example") { value ->
            assertEquals(OtpKind.MOTP, value.kind)
            assertEquals(16, value.copySecret().size)
        }
    }

    @Test
    fun labelIsStrippedOfTheIssuerPrefix() {
        success("otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example") { value ->
            assertEquals("alice", value.label)
        }
        success("otpauth://totp/Other:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example") { value ->
            // A different issuer prefix stays part of the label.
            assertEquals("Other:alice", value.label)
        }
    }

    @Test
    fun spaceCharactersAreEncodedBeforeParsing() {
        success("otpauth://totp/alice example?secret=JBSWY3DPEHPK3PXP") { value ->
            assertEquals("alice example", value.label)
        }
    }

    @Test
    fun plusInAQueryValueIsASpace() {
        success("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&issuer=ACME+Co") { value ->
            assertEquals("ACME Co", value.issuer)
        }
    }

    @Test
    fun canonicalAndUnpaddedBase32SecretsAreAccepted() {
        success("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP") { value ->
            assertEquals(10, value.copySecret().size)
        }
        success("otpauth://totp/alice?secret=jbswy3dpehpk3pxp") { value ->
            assertEquals(10, value.copySecret().size)
        }
    }

    @Test
    fun malformedInputFailsWithExplicitOutcomes() {
        assertEquals(OtpParseError.INVALID_URI, failure(null))
        assertEquals(OtpParseError.INVALID_URI, failure(""))
        assertEquals(OtpParseError.INVALID_URI, failure("http://example.com"))
        assertEquals(OtpParseError.INVALID_URI, failure("otpauth:totp?secret=JBSWY3DPEHPK3PXP"))
        assertEquals(OtpParseError.INVALID_URI, failure("otpauth://totp/alice?secret=JBSW%G"))
        assertEquals(OtpParseError.UNSUPPORTED_TYPE, failure("otpauth://yubikey/alice?secret=JBSWY3DPEHPK3PXP"))
        assertEquals(OtpParseError.MISSING_SECRET, failure("otpauth://totp/alice"))
        assertEquals(OtpParseError.MISSING_SECRET, failure("otpauth://totp/alice?secret="))
        assertEquals(OtpParseError.MISSING_COUNTER, failure("otpauth://hotp/alice?secret=JBSWY3DPEHPK3PXP"))
        assertEquals(OtpParseError.DUPLICATE_PARAMETER,
            failure("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&secret=JBSWY3DPEHPK3PXP"))
        assertEquals(OtpParseError.INVALID_NUMBER, failure("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&period=thirty"))
        assertEquals(OtpParseError.INVALID_PARAMETERS, failure("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&period=0"))
        assertEquals(OtpParseError.INVALID_PARAMETERS, failure("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&digits=10"))
        assertEquals(OtpParseError.INVALID_PARAMETERS, failure("otpauth://steam/alice?secret=JBSWY3DPEHPK3PXP&digits=6"))
        assertEquals(OtpParseError.INVALID_ALGORITHM, failure("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&algorithm=MD5"))
        assertEquals(OtpParseError.INVALID_SECRET, failure("otpauth://totp/alice?secret=1"))
        assertEquals(OtpParseError.INVALID_SECRET, failure("otpauth://totp/alice?secret=JBSWY3DPEHPK3PX8"))
        assertEquals(OtpParseError.INVALID_SECRET, failure("otpauth://motp/alice?secret=000102030405060"))
        assertEquals(OtpParseError.INVALID_SECRET, failure("otpauth://motp/alice?secret=notahexsecret"))
    }

    @Test
    fun oversizedInputIsRejectedEarly() {
        val longLabel = "a".repeat(InputPolicy.MAX_URI_CHARS)
        assertEquals(OtpParseError.INPUT_TOO_LONG, failure("otpauth://totp/$longLabel?secret=JBSWY3DPEHPK3PXP"))
        val longText = "a".repeat(InputPolicy.MAX_TEXT_CHARS + 1)
        assertEquals(OtpParseError.TEXT_TOO_LONG, failure("otpauth://totp/$longText?secret=JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun tagCountIsBounded() {
        val builder = StringBuilder("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP")
        repeat(InputPolicy.MAX_TAGS) { builder.append("&tags=t").append(it) }
        success(builder.toString()) { value ->
            assertEquals(InputPolicy.MAX_TAGS, value.tags.size)
        }
        builder.append("&tags=one-too-many")
        assertEquals(OtpParseError.TOO_MANY_TAGS, failure(builder.toString()))
    }

    @Test
    fun failureNamesAreStable() {
        // The legacy Entry adapter reports error.name to users; the set is part of its message.
        val names = OtpParseError.entries.map { it.name }
        assertEquals(true, names.contains("INVALID_URI"))
        assertEquals(true, names.contains("MISSING_SECRET"))
        assertEquals(true, names.contains("MISSING_COUNTER"))
        assertEquals(true, names.contains("INVALID_SECRET"))
        assertEquals(true, names.contains("INPUT_TOO_LONG"))
    }
}
