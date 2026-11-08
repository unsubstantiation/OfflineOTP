package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.ScreenTimerController

/** UI-thread clipboard lease. Clearing is best effort and never erases a different current value. */
class ExpiringClipboard(
    private val scheduler: ScreenTimerController.Scheduler,
    private val clipboard: Port,
    private val timeoutMillis: Long = 30_000L
) : AutoCloseable {
    interface Port {
        fun read(): String?
        fun write(text: String)
    }
    init { require(timeoutMillis > 0) }
    private var secret: String? = null
    private var expiry: Runnable? = null
    private var closed = false

    fun copy(text: String): Boolean {
        if (closed) return false
        try { clipboard.write(text) } catch (_: Exception) { return false }
        expiry?.let(scheduler::remove)
        secret = text
        val callback = object : Runnable {
            override fun run() {
                if (closed || expiry !== this) return
                expiry = null
                clearMatchingValue()
            }
        }
        expiry = callback
        try { scheduler.postDelayed(callback, timeoutMillis) } catch (_: Exception) {
            expiry = null
            clearMatchingValue()
            return false
        }
        return true
    }

    private fun clearMatchingValue() {
        val expected = secret ?: return
        secret = null
        try {
            if (clipboard.read() == expected) clipboard.write("")
        } catch (_: Exception) {
            // An unavailable OS clipboard cannot be reliably cleared. Drop our reference anyway.
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        expiry?.let(scheduler::remove)
        expiry = null
        clearMatchingValue()
    }
}
