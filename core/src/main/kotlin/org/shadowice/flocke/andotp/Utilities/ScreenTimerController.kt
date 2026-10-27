package org.shadowice.flocke.andotp.Utilities

/**
 * Main-thread controller for the 1-second token tick and the inactivity auto-lock timer.
 * Holds no key, session, Context, or saved state.
 *
 * The inactivity deadline is kept in [Scheduler.elapsedRealtime] (which advances during deep
 * sleep), while the callback itself can only run while the foreground is scheduled. A screen-off
 * or background period therefore still counts towards the idle time: [suspendForeground] drops
 * the callback, [hasInactivityExpired] reports whether the deadline passed meanwhile, and the
 * screen re-arms the timer from the current time when it comes back.
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
    private var deadline: Long? = null
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
        deadline = started + delayMillis
        val scheduled = object : Runnable {
            override fun run() {
                if (closed || idle !== this) return
                val remaining = delayMillis - (scheduler.elapsedRealtime() - started)
                if (remaining > 0) {
                    scheduler.postDelayed(this, minOf(1000L, remaining))
                    return
                }
                idle = null
                deadline = null
                onInactivity?.run()
            }
        }
        idle = scheduled
        // Like CountDownTimer, check elapsed time on wake, not just Handler uptime.
        scheduler.postDelayed(scheduled, minOf(1000L, delayMillis))
    }

    /** Stops the pending callback but keeps the deadline, so background time is not free time. */
    fun suspendInactivity() {
        val previous = idle
        idle = null
        if (previous != null) scheduler.remove(previous)
    }

    /**
     * True when an armed inactivity deadline has passed, including during a pause in which the
     * callback could not run. A fired or cancelled timer never reports expired.
     */
    fun hasInactivityExpired(): Boolean {
        val limit = deadline ?: return false
        return !closed && scheduler.elapsedRealtime() >= limit
    }

    fun cancelInactivity() {
        val previous = idle
        idle = null
        deadline = null
        if (previous != null) scheduler.remove(previous)
    }

    /** Pauses both loops, cancelling the inactivity deadline (used when the screen closes). */
    fun pause() {
        stopTicks()
        cancelInactivity()
    }

    /** Pauses both loops but preserves the inactivity deadline across a background period. */
    fun suspendForeground() {
        stopTicks()
        suspendInactivity()
    }

    /** Permanently drops the screen callbacks, including when an obsolete runnable is retained. */
    override fun close() {
        pause()
        closed = true
        onTick = null
        onInactivity = null
    }
}
