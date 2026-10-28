/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the platform-free QR import router: decoded text is classified into a
 * single otpauth result, a Google Authenticator migration batch, or unrecognized/invalid content
 * without any image or zxing dependency.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrImportTest {

    private val exporterSample = "CikKCkhlbGxvId6tvu8SCHRlc3R1c2VyGgtleGFtcGxlLmNvbSABKAEwAgo8" +
        "CgpXb3JsZCEgICAgEhx0ZXN0LWdpdGh1Yi51c2VyQGV4YW1wbGUuY29tGgpnaXRodWIuY29tIAEoATACCjcKCldv" +
        "cmxkISAgICESD2Fub3RoZXJ0ZXN0dXNlchoSc29tZWV4YW1wbGVzZXJ2aWNlIAEoATACCjIKCldvcmxkISAgICIS" +
        "CmZvdXJ0aHVzZXIaEnNvbWVleGFtcGxlc2VydmljZSABKAEwAhACGAEgAA=="

    @Test
    fun blankAndNullContentIsUnrecognized() {
        assertEquals(QrImport.Outcome.Unrecognized, QrImport.classify(null))
        assertEquals(QrImport.Outcome.Unrecognized, QrImport.classify(""))
        assertEquals(QrImport.Outcome.Unrecognized, QrImport.classify("   \n "))
        // Non-OTP content still goes through the otpauth parser and is explicitly invalid.
        val outcome = QrImport.classify("https://example.com/not-otp")
        assertTrue(outcome is QrImport.Outcome.Invalid)
    }

    @Test
    fun aSingleOtpauthUriIsParsedAndOwnsAValidatedOtp() {
        val uri = "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        val outcome = QrImport.classify(uri)
        assertTrue(outcome is QrImport.Outcome.Otp)
        val otp = (outcome as QrImport.Outcome.Otp).result.value
        assertEquals(OtpKind.TOTP, otp.kind)
        assertEquals("Example", otp.issuer)
        assertEquals("alice", otp.label)
    }

    @Test
    fun aMalformedOtpauthUriIsInvalidWithTheParserReason() {
        val outcome = QrImport.classify("otpauth://totp/Example:alice?issuer=Example")
        assertTrue(outcome is QrImport.Outcome.Invalid)
        assertEquals(OtpParseError.MISSING_SECRET.name, (outcome as QrImport.Outcome.Invalid).reason)
    }

    @Test
    fun aGoogleMigrationUriIsRoutedToTheMigrationParser() {
        val outcome = QrImport.classify("otpauth-migration://offline?data=$exporterSample")
        assertTrue(outcome is QrImport.Outcome.Migration)
        val accounts = (outcome as QrImport.Outcome.Migration).result.payload.takeAccounts()
        assertEquals(4, accounts.size)
    }

    @Test
    fun aBrokenMigrationUriIsInvalidRatherThanUnrecognized() {
        // The scheme is recognized as a migration, so a bad payload is an explicit invalid result.
        val outcome = QrImport.classify("otpauth-migration://online?data=AA")
        assertTrue(outcome is QrImport.Outcome.Invalid)
        assertEquals(MigrationError.INVALID_URI.name, (outcome as QrImport.Outcome.Invalid).reason)
    }
}
