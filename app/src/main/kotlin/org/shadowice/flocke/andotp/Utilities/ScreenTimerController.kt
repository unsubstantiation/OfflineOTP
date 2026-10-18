package org.shadowice.flocke.andotp.Utilities

/**
 * Main-thread controller for the 1-second token tick and the inactivity auto-lock timer.
 * Holds no key, session, Context, or saved state.
 */
class ScreenTimerController(
    private val scheduler: Scheduler,
    onTick: Runnable,
    onInactivity: Runnable
) : AutoCloseable {
    interface Scheduler {
        fun postDelayed(runnable: Runnable, delayMillis: Long)
        fun remove(runnable: Runnable)
        fun elapsedRealtime(): Long
    }

    private var onTick: Runnable? = onTick
    private var onInactivity: Runnable? = onInactivity
    private var tick: Runnable? = null
    private var idle: Runnable? = null
    private var closed = false

    /** Idempotent: repeated resumes/drag-end callbacks cannot create duplicate tick loops. */
    fun startTicks() {
        if (closed || tick != null) return
        val scheduled = object : Runnable {
            override fun run() {
                if (closed || tick !== this) return
                onTick?.run()
                if (!closed && tick === this) scheduler.postDelayed(this, 1000)
            }
        }
        tick = scheduled
        scheduler.postDelayed(scheduled, 0)
    }

    fun stopTicks() {
        val previous = tick
        tick = null
        if (previous != null) scheduler.remove(previous)
    }

    /** Zero/negative delay means disabled; every restart invalidates the previous expiry. */
    fun restartInactivity(delayMillis: Long) {
        cancelInactivity()
        if (closed || delayMillis <= 0) return
        val started = scheduler.elapsedRealtime()
        val scheduled = object : Runnable {
            override fun run() {
                if (closed || idle !== this) return
                val remaining = delayMillis - (scheduler.elapsedRealtime() - started)
                if (remaining > 0) {
                    scheduler.postDelayed(this, minOf(1000L, remaining))
                    return
                }
                idle = null
                onInactivity?.run()
            }
        }
        idle = scheduled
        // Like CountDownTimer, check elapsed time on wake, not just Handler uptime.
        scheduler.postDelayed(scheduled, minOf(1000L, delayMillis))
    }

    fun cancelInactivity() {
        val previous = idle
        idle = null
        if (previous != null) scheduler.remove(previous)
    }

    fun pause() {
        stopTicks()
        cancelInactivity()
    }

    /** Permanently drops the screen callbacks, including when an obsolete runnable is retained. */
    override fun close() {
        pause()
        closed = true
        onTick = null
        onInactivity = null
    }
}
