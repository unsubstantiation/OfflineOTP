package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSessionLifetimeTest {
    private class Clock : ScreenTimerController.Scheduler {
        var now = 0L
        val pending = LinkedHashSet<Runnable>()
        override fun postDelayed(runnable: Runnable, delayMillis: Long) { pending.add(runnable) }
        override fun remove(runnable: Runnable) { pending.remove(runnable) }
        override fun elapsedRealtime() = now
    }

    @Test
    fun activityResetsOnlyALiveDeadlineAndOldCallbacksCannotLock() {
        val clock = Clock()
        var locks = 0
        val lifetime = DesktopSessionLifetime(clock, 10) { locks++ }
        val old = clock.pending.single()
        clock.now = 9_000
        lifetime.activity()
        clock.now = 10_000
        old.run()
        assertEquals(0, locks)
        clock.now = 19_000
        val due = clock.pending.single()
        clock.pending.remove(due) // A real scheduler dequeues a one-shot callback before running it.
        due.run()
        assertEquals(1, locks)
        lifetime.activity()
        lifetime.close()
        assertEquals(1, locks)
        assertTrue(clock.pending.isEmpty())
    }

    @Test
    fun firstInputAfterExpiryLocksInsteadOfExtendingTheDeadline() {
        val clock = Clock()
        var locks = 0
        val lifetime = DesktopSessionLifetime(clock, 10) { locks++ }
        clock.now = 10_001 // Simulate a blocked UI thread; its timer hasn't run yet.
        lifetime.activity()
        assertEquals(1, locks)
        assertTrue(clock.pending.isEmpty())
    }

    @Test
    fun disabledTimeoutStillLocksOnDisposalAndDoesNotOverflow() {
        for (seconds in listOf(0, -1)) {
            val clock = Clock()
            var locks = 0
            val lifetime = DesktopSessionLifetime(clock, seconds) { locks++ }
            lifetime.activity()
            assertTrue(clock.pending.isEmpty())
            lifetime.close()
            lifetime.close()
            assertEquals(1, locks)
        }
        val clock = Clock()
        var locks = 0
        val lifetime = DesktopSessionLifetime(clock, Int.MAX_VALUE) { locks++ }
        clock.now = Int.MAX_VALUE.toLong() * 1000L - 1
        lifetime.activity()
        assertEquals(0, locks)
        lifetime.close()
    }

    @Test
    fun failedCleanupIsStillTerminalAndDropsTimerCallbacks() {
        val clock = Clock()
        var locks = 0
        val lifetime = DesktopSessionLifetime(clock, 10) { locks++; error("cleanup failure") }
        val stale = clock.pending.single()
        try { lifetime.close() } catch (_: IllegalStateException) { }
        stale.run()
        lifetime.activity()
        lifetime.close()
        assertEquals(1, locks)
        assertTrue(clock.pending.isEmpty())
    }
}
