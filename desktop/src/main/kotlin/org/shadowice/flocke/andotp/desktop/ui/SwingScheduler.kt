package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Utilities.ScreenTimerController

import javax.swing.Timer

/**
 * [ScreenTimerController.Scheduler] backed by Swing timers, so every token tick and inactivity
 * callback runs on the event dispatch thread alongside table repaints. Timers are one-shot: the
 * shared controller re-arms its tick loop itself, exactly as on Android. Heavyweight by package;
 * headless tests drive a fake scheduler instead.
 */
class SwingScheduler : ScreenTimerController.Scheduler {

    private val timers = HashMap<Runnable, Timer>()

    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        remove(runnable)
        val timer = Timer(delayMillis.toInt()) { runnable.run() }.apply {
            isRepeats = false
            start()
        }
        timers[runnable] = timer
    }

    override fun remove(runnable: Runnable) {
        timers.remove(runnable)?.stop()
    }

    override fun elapsedRealtime(): Long = System.currentTimeMillis()
}
