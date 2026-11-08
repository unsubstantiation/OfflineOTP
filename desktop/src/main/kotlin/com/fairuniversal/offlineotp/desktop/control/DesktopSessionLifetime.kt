package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.ScreenTimerController

/** UI-thread session lifetime, independent of Swing/Compose. Closing is terminal and locks once. */
class DesktopSessionLifetime(
    scheduler: ScreenTimerController.Scheduler,
    timeoutSeconds: Int,
    onLock: () -> Unit
) : AutoCloseable {
    private var lockAction: (() -> Unit)? = onLock
    private val timeoutMillis = timeoutSeconds.coerceAtLeast(0).toLong() * 1000L
    private val timer = ScreenTimerController(scheduler, Runnable {}, Runnable { close() })

    init { timer.restartInactivity(timeoutMillis) }

    /** The first input after an overdue timer must lock, not revive the expired session. */
    fun activity() {
        if (lockAction == null) return
        if (timer.hasInactivityExpired()) close() else timer.restartInactivity(timeoutMillis)
    }

    override fun close() {
        val action = lockAction ?: return
        lockAction = null
        timer.close()
        action()
    }
}
