package com.fairuniversal.offlineotp.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Token grouping and step arithmetic. These are the rules the two existing clients implemented
 * separately (Android's progress bar, Swing's token colouring), so they are pinned here.
 */
class TokenDisplayTest {

    @Test
    fun sixDigitCodesSplitIntoHalves() {
        assertEquals("123 456", TokenDisplay.group("123456"))
    }

    @Test
    fun eightDigitCodesSplitIntoHalves() {
        assertEquals("1234 5678", TokenDisplay.group("12345678"))
    }

    @Test
    fun sevenDigitCodesStayWhole() {
        assertEquals("1234567", TokenDisplay.group("1234567"))
    }

    @Test
    fun steamAlphabetCodesStayWhole() {
        assertEquals("7BCDF", TokenDisplay.group("7BCDF"))
        assertEquals("BCD7F", TokenDisplay.group("BCD7F"))
    }

    @Test
    fun shortCodesStayWhole() {
        assertEquals("1234", TokenDisplay.group("1234"))
    }

    @Test
    fun groupingCanBeTurnedOff() {
        assertEquals("123456", TokenDisplay.group("123456", group = false))
    }

    @Test
    fun aFreshStepReportsTheWholeStep() {
        assertEquals(30, TokenDisplay.remainingSeconds(30, 0L))
        assertEquals(30, TokenDisplay.remainingSeconds(30, 999L))
        assertEquals(30, TokenDisplay.remainingSeconds(30, 30_000L))
    }

    @Test
    fun remainingSecondsCountsDownInsideTheStep() {
        assertEquals(29, TokenDisplay.remainingSeconds(30, 1_000L))
        assertEquals(1, TokenDisplay.remainingSeconds(30, 29_000L))
        assertEquals(20, TokenDisplay.remainingSeconds(30, 10_000L))
    }

    @Test
    fun motpUsesItsOwnStep() {
        assertEquals(10, TokenDisplay.remainingSeconds(10, 0L))
        assertEquals(1, TokenDisplay.remainingSeconds(10, 9_000L))
    }

    @Test
    fun aClockThatMovesBackwardsStaysInsideTheStep() {
        assertEquals(1, TokenDisplay.remainingSeconds(30, -1_000L))
        assertEquals(30, TokenDisplay.remainingSeconds(30, -30_000L))
    }

    @Test
    fun aZeroStepHasNoCountdown() {
        assertEquals(0, TokenDisplay.remainingSeconds(0, 5_000L))
        assertEquals(0.0f, TokenDisplay.progress(0, 5_000L))
    }

    @Test
    fun progressStaysWithinTheStep() {
        assertEquals(1.0f, TokenDisplay.progress(30, 0L))
        assertEquals(0.5f, TokenDisplay.progress(30, 15_000L))
        assertTrue(TokenDisplay.progress(30, 29_999L) > 0f)
        assertFalse(TokenDisplay.progress(30, 5_000L) > 1.0f)
    }
}
