/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the main-screen timer policy, driven by a manually advanced fake scheduler.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimerControllerTest {
    private class RecordingScheduler : ScreenTimerController.Scheduler {
        var now = 0L
        val posted = LinkedHashMap<Runnable, Long>()

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            posted[runnable] = now + delayMillis
        }

        override fun remove(runnable: Runnable) {
            posted.remove(runnable)
        }

        override fun elapsedRealtime(): Long = now

        /** Runs every posted runnable whose time has come, repeatedly, until the clock is idle. */
        fun advance(milliseconds: Long) {
            val target = now + milliseconds
            while (true) {
                val next = posted.values.minOrNull() ?: break
                if (next > target) break
                now = next
                val due = posted.entries.filter { it.value <= now }.map { it.key }
                for (runnable in due) {
                    posted.remove(runnable)
                    runnable.run()
                }
            }
            now = target
        }

        fun pending(): Int = posted.size
    }

    private class Counters {
        var ticks = 0
        var inactivity = 0
    }

    @Test
    fun ticksRepeatWhileStarted() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.startTicks()
        scheduler.advance(2500)
        assertEquals(3, counters.ticks)  // at 0, 1000 and 2000 ms
        assertEquals(1, scheduler.pending())
        controller.close()
    }

    @Test
    fun repeatedStartsDoNotDuplicateTheLoop() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.startTicks()
        controller.startTicks()
        scheduler.advance(2000)
        // One firing per second (t=0, 1000, 2000) and exactly one pending callback:
        // a duplicated loop would leave more scheduled and tick more often.
        assertEquals(3, counters.ticks)
        assertEquals(1, scheduler.pending())
        controller.close()
    }

    @Test
    fun stopTicksCancelsPendingCallbacks() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.startTicks()
        controller.stopTicks()
        assertEquals(0, scheduler.pending())
        scheduler.advance(5000)
        assertEquals(0, counters.ticks)
        controller.close()
    }

    @Test
    fun anObsoleteTickCallbackCannotRestartAStoppedLoop() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.startTicks()
        val firstTick = scheduler.posted.keys.first()
        controller.stopTicks()
        // A runnable that was already dequeued by the real main loop may still run; it must
        // neither tick nor re-schedule itself.
        firstTick.run()
        assertEquals(0, counters.ticks)
        scheduler.advance(5000)
        assertEquals(0, counters.ticks)
        assertEquals(0, scheduler.pending())
        controller.close()
    }

    @Test
    fun inactivityFiresAfterTheDelay() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.restartInactivity(3000)
        scheduler.advance(3000)
        assertEquals(1, counters.inactivity)
        assertEquals(0, scheduler.pending())
        controller.close()
    }

    @Test
    fun restartInvalidatesThePreviousExpiry() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.restartInactivity(1000)
        scheduler.advance(500)
        controller.restartInactivity(5000)
        scheduler.advance(600)
        assertEquals(0, counters.inactivity)
        // The second expiry runs from the restart at t=500, so it fires at t=5500.
        scheduler.advance(4500)
        assertEquals(1, counters.inactivity)
        assertEquals(0, scheduler.pending())
        controller.close()
    }

    @Test
    fun zeroOrNegativeDelayDisablesInactivity() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.restartInactivity(0)
        controller.restartInactivity(-5)
        assertEquals(0, scheduler.pending())
        scheduler.advance(60000)
        assertEquals(0, counters.inactivity)
        controller.close()
    }

    @Test
    fun pauseCancelsBothLoops() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.startTicks()
        controller.restartInactivity(1000)
        controller.pause()
        assertEquals(0, scheduler.pending())
        scheduler.advance(10000)
        assertEquals(0, counters.ticks)
        assertEquals(0, counters.inactivity)
        controller.close()
    }

    @Test
    fun backgroundTimeCountsTowardsTheInactivityDeadline() {
        // Reproduces the andOTP #712 report: the screen goes off while the app is open, the
        // callback cannot run, and the app is still unlocked when the user comes back.
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.restartInactivity(3000)
        scheduler.advance(1000)
        assertFalse(controller.hasInactivityExpired())

        controller.suspendForeground()
        assertEquals(0, scheduler.pending())
        scheduler.advance(60000)

        assertTrue(controller.hasInactivityExpired())
        assertFalse("a suspended timer must not fire in the background", counters.inactivity > 0)
        controller.close()
    }

    @Test
    fun restartingAfterBackgroundTimeRearmsFromNow() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.restartInactivity(3000)
        controller.suspendForeground()
        scheduler.advance(60000)
        controller.restartInactivity(3000)

        assertFalse(controller.hasInactivityExpired())
        scheduler.advance(2999)
        assertEquals(0, counters.inactivity)
        scheduler.advance(1)
        assertEquals(1, counters.inactivity)
        controller.close()
    }

    @Test
    fun aFiredOrCancelledTimerNoLongerReportsExpired() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.restartInactivity(1000)
        scheduler.advance(1000)
        assertEquals(1, counters.inactivity)
        assertFalse(controller.hasInactivityExpired())

        controller.restartInactivity(1000)
        controller.cancelInactivity()
        scheduler.advance(10000)
        assertFalse(controller.hasInactivityExpired())
        controller.close()
    }

    @Test
    fun pauseForgetsTheDeadlineWhileSuspendingKeepsIt() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.restartInactivity(1000)
        controller.pause()
        scheduler.advance(10000)
        assertFalse("pause() is the closing path and must drop the deadline",
            controller.hasInactivityExpired())

        controller.restartInactivity(1000)
        controller.suspendForeground()
        scheduler.advance(10000)
        assertTrue(controller.hasInactivityExpired())
        controller.close()
    }

    @Test
    fun closeIsPermanent() {
        val scheduler = RecordingScheduler()
        val counters = Counters()
        val controller = ScreenTimerController(scheduler, { counters.ticks++ }, { counters.inactivity++ })

        controller.startTicks()
        controller.restartInactivity(1000)
        controller.close()
        assertEquals(0, scheduler.pending())
        controller.startTicks()
        controller.restartInactivity(1000)
        scheduler.advance(10000)
        assertEquals(0, counters.ticks)
        assertEquals(0, counters.inactivity)
        assertTrue(scheduler.posted.isEmpty())
    }
}
