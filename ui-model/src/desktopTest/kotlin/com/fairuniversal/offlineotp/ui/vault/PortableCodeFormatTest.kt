package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.ui.portable.PortableClipboardPolicy
import com.fairuniversal.offlineotp.ui.portable.PortableCodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replaces the former Swift CodeFormatTests with executable shared-code coverage. */
class PortableCodeFormatTest {
    @Test fun groupingRetainsNumericSteamAndMotpCharacters() {
        for ((code, expected) in listOf("324550" to "324 550", "18948850" to "189 488 50",
            "12345" to "123 45", "123" to "123", "2KM2P" to "2KM 2P", "693ae2" to "693 ae2")) {
            assertEquals(expected, PortableCodeFormat.grouped(code, 3))
        }
        for (code in listOf("324550", "2KM2P", "693ae2", "1", "", "123456789")) {
            assertEquals(code, PortableCodeFormat.grouped(code, 3).replace(" ", ""))
            assertEquals(code, PortableCodeFormat.grouped(code, 0))
            assertEquals(code, PortableCodeFormat.grouped(code, -1))
        }
    }

    @Test fun spokenCodesPreserveEachCharacter() {
        assertEquals("3 2 4 5 5 0", PortableCodeFormat.spoken("324550"))
        assertEquals("1 8 9 4 8 8 5 0", PortableCodeFormat.spoken("18948850"))
        assertEquals("2 K M 2 P", PortableCodeFormat.spoken("2KM2P"))
        assertEquals("6 9 3 a e 2", PortableCodeFormat.spoken("693ae2"))
        assertEquals("", PortableCodeFormat.spoken(""))
    }

    @Test fun countdownWordingHandlesSingularZeroAndAbsentValues() {
        assertEquals("12 seconds left", PortableCodeFormat.remainingDescription(12))
        assertEquals("1 second left", PortableCodeFormat.remainingDescription(1))
        assertEquals("0 seconds left", PortableCodeFormat.remainingDescription(0))
        assertEquals("", PortableCodeFormat.remainingDescription(null))
    }

    @Test fun stepsCountDownAndRestartAtFullPeriod() {
        val start = 1_700_000_010L
        assertEquals(30, PortableCodeFormat.secondsRemaining("TOTP", 30, start))
        assertEquals(1, PortableCodeFormat.secondsRemaining("TOTP", 30, start + 29))
        assertEquals(30, PortableCodeFormat.secondsRemaining("TOTP", 30, start + 30))
        assertTrue(1.0 == PortableCodeFormat.fractionRemaining("TOTP", 30, start))
        assertTrue(0.5 == PortableCodeFormat.fractionRemaining("TOTP", 30, start + 15))
        assertEquals(1, PortableCodeFormat.secondsRemaining("STEAM", 30, -1))
    }

    @Test fun motpHasFixedTenSecondSteps() {
        val start = 1_700_000_000L
        assertEquals(10, PortableCodeFormat.secondsRemaining("MOTP", 30, start))
        assertEquals(1, PortableCodeFormat.secondsRemaining("MOTP", 30, start + 9))
        assertEquals(10, PortableCodeFormat.secondsRemaining("MOTP", 60, start))
    }

    @Test fun nonRolloverAndInvalidPeriodsHaveNoCountdown() {
        for (kind in listOf("HOTP", "NOTE", "UNKNOWN")) {
            assertNull(PortableCodeFormat.secondsRemaining(kind, 30, 1_700_000_000L))
            assertNull(PortableCodeFormat.stepSeconds(kind, 30))
            assertTrue(0.0 == PortableCodeFormat.fractionRemaining(kind, 30, 0))
        }
        assertNull(PortableCodeFormat.secondsRemaining("TOTP", 0, 0))
        assertNull(PortableCodeFormat.secondsRemaining("TOTP", -30, 0))
    }

    @Test fun clipboardExpiryIsBoundedByBothTimeoutAndTokenExpiry() {
        assertTrue(30.0 == PortableClipboardPolicy.expirySeconds)
        assertTrue(130.0 == PortableClipboardPolicy.expiry(100.0, 900.0)!!)
        assertTrue(105.0 == PortableClipboardPolicy.expiry(100.0, 105.0)!!)
        assertNull(PortableClipboardPolicy.expiry(100.0, 100.0))
        assertNull(PortableClipboardPolicy.expiry(100.0, 99.0))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(PortableClipboardPolicy.expiry(invalid, 100.0))
            assertNull(PortableClipboardPolicy.expiry(100.0, invalid))
        }
    }
}
