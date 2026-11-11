package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.Constants

/** Save before applying to the presenter; stale/expired requests cannot change the current view. */
class DesktopLayoutSettings(private val state: DesktopVaultState,
                            private val read: () -> Constants.CardLayouts,
                            private val write: (Constants.CardLayouts) -> Boolean) {
    class Request internal constructor(val current: Constants.CardLayouts) {
        var failed = false
            internal set
    }
    var pending: Request? = null
        private set
    private var closed = false
    fun open() { if (!closed && !state.isLocked) pending = Request(read()) }
    fun dismiss(request: Request) { if (pending === request) pending = null }
    fun close() { closed = true; pending = null }
    fun submit(request: Request, layout: Constants.CardLayouts, active: () -> Boolean,
               apply: (Constants.CardLayouts) -> Unit): Boolean {
        if (!valid(request) || !active()) return false
        val saved = try { write(layout) } catch (_: Exception) { false }
        if (!saved) { request.failed = true; return false }
        // A slow write can persist a future preference while expiry closes the current vault.
        if (!valid(request) || !active()) return false
        pending = null; apply(layout); return true
    }
    private fun valid(request: Request) = !closed && !state.isLocked && pending === request
}
