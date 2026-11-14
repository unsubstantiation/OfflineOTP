package com.fairuniversal.offlineotp.desktop.compose

import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real JDK event queue/timers; no display, Swing widgets, Compose runtime or compile stubs. */
class AwtSchedulerTest {
    @Test
    fun replacingATimerDeliversOnlyItsNewestCallbackOnTheEventThread() {
        val scheduler = AwtScheduler()
        val fired = CountDownLatch(1)
        val calls = AtomicInteger()
        var onEventThread = false
        try {
            EventQueue.invokeAndWait {
                val task = Runnable {
                    onEventThread = EventQueue.isDispatchThread()
                    calls.incrementAndGet()
                    fired.countDown()
                }
                scheduler.postDelayed(task, 60_000)
                scheduler.postDelayed(task, 0)
            }
            assertTrue("scheduled callback never arrived", fired.await(5, TimeUnit.SECONDS))
            assertTrue(onEventThread)
            assertEquals(1, calls.get())
        } finally { EventQueue.invokeAndWait { scheduler.close() } }
    }

    @Test
    fun removingAndClosingInvalidateCallbacksEvenWhenTheyAreAlreadyQueued() {
        val scheduler = AwtScheduler()
        val calls = AtomicInteger()
        EventQueue.invokeAndWait {
            val task = Runnable { calls.incrementAndGet() }
            scheduler.postDelayed(task, 0)
            scheduler.remove(task)
            scheduler.postDelayed(task, 0)
            scheduler.close()
            scheduler.postDelayed(task, 0) // A closed session cannot schedule work again.
            scheduler.close()
        }
        EventQueue.invokeAndWait {} // Drain callbacks queued behind the close operation.
        assertEquals(0, calls.get())
    }

    @Test
    fun offThreadSchedulingIsRejected() {
        val scheduler = AwtScheduler()
        var rejected = false
        try {
            scheduler.postDelayed(Runnable {}, 0)
        } catch (_: IllegalStateException) {
            rejected = true
        } finally {
            EventQueue.invokeAndWait { scheduler.close() }
        }
        assertTrue(rejected)
    }
}
