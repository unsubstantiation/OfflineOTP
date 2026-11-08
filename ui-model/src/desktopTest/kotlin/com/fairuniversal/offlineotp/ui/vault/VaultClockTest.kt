package com.fairuniversal.offlineotp.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultClockTest {
    @Test
    fun everySampleReadsCurrentWallTimeIncludingForwardAndBackwardCorrections() {
        var now = 1_234L
        var reads = 0
        val clock = VaultClock({ reads++; now })
        assertEquals(VaultClockSample(1_234L, 766L), clock.sample())
        now = 90_000L
        assertEquals(VaultClockSample(90_000L, 1_000L), clock.sample())
        now = 5_678L
        assertEquals(VaultClockSample(5_678L, 322L), clock.sample())
        assertEquals(3, reads)
    }

    @Test
    fun resumeSamplesTheNewTimeRatherThanCountingMissedTicks() {
        var now = 0L
        val clock = VaultClock({ now })
        clock.sample()
        now = 8 * 60 * 60 * 1_000L + 125L
        assertEquals(VaultClockSample(now, 875L), clock.sample())
    }

    @Test
    fun boundaryDelaysStayPositiveForNegativeAndExtremeTimes() {
        for (time in listOf(Long.MIN_VALUE, -1_001L, -1_000L, -1L, 0L, 999L, Long.MAX_VALUE)) {
            val sample = VaultClock({ time }).sample()
            assertEquals(time, sample.nowMillis)
            assertTrue(sample.delayMillis in 1L..1_000L)
        }
        assertEquals(1L, VaultClock({ -1L }).sample().delayMillis)
        assertEquals(1_000L, VaultClock({ 0L }).sample().delayMillis)
        assertEquals(25L, VaultClock({ 125L }, tickMillis = 50L).sample().delayMillis)
    }

    @Test
    fun invalidIntervalsAreRejectedBeforeReadingTheClock() {
        var reads = 0
        for (interval in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertTrue(runCatching { VaultClock({ reads++; 0L }, interval) }.isFailure)
        }
        assertEquals(0, reads)
    }
}
