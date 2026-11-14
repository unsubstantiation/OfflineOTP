package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.ScreenTimerController

/** UI-thread session lifetime, independent of Swing/Compose. Closing is terminal and locks once. */
class DesktopSessionLifetime(
    scheduler: ScreenTimerController.Scheduler,
    timeoutSeconds: Int,
    startImmediately: Boolean = true,
    onLock: () -> Unit
) : AutoCloseable {
    private var lockAction: (() -> Unit)? = onLock
    private var timeoutMillis = timeoutSeconds.coerceAtLeast(0).toLong() * 1000L
    private val timer = ScreenTimerController(scheduler, Runnable {}, Runnable { close() })

    private var started = false
    init { if (startImmediately) start() }

    /** Compose installs side effects only after the remembered owner enters composition. */
    fun start() {
        if (started || lockAction == null) return
        started = true
        timer.restartInactivity(timeoutMillis)
    }

    /** The first input after an overdue timer must lock, not revive the expired session. */
    fun activity() {
        if (checkActive()) timer.restartInactivity(timeoutMillis)
    }

    /** Check without resetting the deadline, including before a settings write. */
    fun checkActive(): Boolean {
        if (lockAction == null || !started) return false
        if (timer.hasInactivityExpired()) { close(); return false }
        return true
    }

    /** Updating the timeout must never revive a terminal or already overdue session. */
    fun changeTimeout(seconds: Int): Boolean {
        if (seconds < 0 || !checkActive()) return false
        timeoutMillis = seconds.toLong() * 1000L
        timer.restartInactivity(timeoutMillis)
        return true
    }

    override fun close() {
        val action = lockAction ?: return
        lockAction = null
        timer.close()
        action()
    }
}
