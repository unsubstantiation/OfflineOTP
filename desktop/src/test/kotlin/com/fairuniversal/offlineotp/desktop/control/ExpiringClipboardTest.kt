package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpiringClipboardTest {
    private class Scheduler : ScreenTimerController.Scheduler {
        val pending = LinkedHashMap<Runnable, Long>()
        override fun elapsedRealtime() = 0L
        override fun postDelayed(runnable: Runnable, delayMillis: Long) { pending[runnable] = delayMillis }
        override fun remove(runnable: Runnable) { pending.remove(runnable) }
        fun fire() { val task = pending.keys.single(); pending.remove(task); task.run() }
    }
    private class Port : ExpiringClipboard.Port {
        var value: String? = null
        var fail = false
        override fun read(): String? { check(!fail); return value }
        override fun write(text: String) { check(!fail); value = text }
    }

    @Test
    fun expiresAfterThirtySecondsAndOldCallbacksCannotClearANewerCopy() {
        val clock = Scheduler(); val port = Port(); val lease = ExpiringClipboard(clock, port)
        assertTrue(lease.copy("first"))
        assertEquals(30_000L, clock.pending.values.single())
        val stale = clock.pending.keys.single()
        assertTrue(lease.copy("second"))
        stale.run()
        assertEquals("second", port.value)
        clock.fire()
        assertEquals("", port.value)
        lease.close()
    }

    @Test
    fun expiryAndClosePreserveOtherClipboardContent() {
        val clock = Scheduler(); val port = Port(); val lease = ExpiringClipboard(clock, port)
        lease.copy("secret")
        port.value = "user replacement"
        clock.fire()
        assertEquals("user replacement", port.value)
        lease.copy("another secret")
        port.value = "another application"
        lease.close()
        assertEquals("another application", port.value)
        assertFalse(lease.copy("late copy"))
        assertTrue(clock.pending.isEmpty())
    }

    @Test
    fun closeClearsMatchingValueAndFailedCopyKeepsTheOldExpiry() {
        val clock = Scheduler(); val port = Port(); val lease = ExpiringClipboard(clock, port)
        lease.copy("secret")
        val original = clock.pending.keys.single()
        port.fail = true
        assertFalse(lease.copy("failed"))
        assertTrue(clock.pending.keys.single() === original)
        port.fail = false
        lease.close()
        assertEquals("", port.value)
        original.run()
        assertTrue(clock.pending.isEmpty())
    }

    @Test
    fun unavailableClipboardDoesNotPreventTerminalCleanup() {
        val clock = Scheduler(); val port = Port(); val lease = ExpiringClipboard(clock, port)
        lease.copy("secret")
        port.fail = true
        lease.close()
        lease.close()
        assertFalse(lease.copy("late"))
        assertTrue(clock.pending.isEmpty())
    }
}
