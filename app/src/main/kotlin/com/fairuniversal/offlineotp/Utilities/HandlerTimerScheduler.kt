package com.fairuniversal.offlineotp.Utilities

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Android main-loop bridge; ScreenTimerController owns cancellation and lifecycle. */
class HandlerTimerScheduler : ScreenTimerController.Scheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        handler.postDelayed(runnable, delayMillis)
    }

    override fun remove(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}
