package com.fairuniversal.offlineotp.desktop.compose

import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import java.awt.EventQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Swing-free timer port. All public calls and callbacks run on the Compose window's AWT thread. */
internal class AwtScheduler : ScreenTimerController.Scheduler, AutoCloseable {
    private class Ticket { var future: ScheduledFuture<*>? = null }
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "OfflineOTP session timer").apply { isDaemon = true }
    }
    private val pending = HashMap<Runnable, Ticket>()
    private var closed = false

    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        check(EventQueue.isDispatchThread())
        if (closed) return
        remove(runnable)
        val ticket = Ticket()
        pending[runnable] = ticket
        ticket.future = executor.schedule({
            EventQueue.invokeLater {
                // Cancellation must also invalidate a callback already queued on the AWT thread.
                if (!closed && pending[runnable] === ticket) {
                    pending.remove(runnable)
                    runnable.run()
                }
            }
        }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    override fun remove(runnable: Runnable) {
        check(EventQueue.isDispatchThread())
        pending.remove(runnable)?.future?.cancel(false)
    }

    override fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L

    override fun close() {
        check(EventQueue.isDispatchThread())
        if (closed) return
        closed = true
        pending.values.forEach { it.future?.cancel(false) }
        pending.clear()
        executor.shutdownNow()
    }
}
