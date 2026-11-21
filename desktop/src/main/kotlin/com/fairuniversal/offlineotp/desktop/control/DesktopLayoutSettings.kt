package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.Constants

/** Save before applying to the presenter; stale/expired requests cannot change the current view. */
class DesktopLayoutSettings(
    private val state: DesktopVaultState,
    private val read: () -> Constants.CardLayouts,
    private val write: (Constants.CardLayouts) -> Boolean,
    private val readHideIssuer: () -> Boolean = { false },
    private val writeHideIssuer: (Boolean) -> Boolean = { true }
) {
    class Request internal constructor(
        val current: Constants.CardLayouts,
        val hideIssuer: Boolean = false
    ) {
        var failed = false
            internal set
    }
    var pending: Request? = null
        private set
    private var closed = false
    fun open() { if (!closed && !state.isLocked) pending = Request(read(), readHideIssuer()) }
    fun dismiss(request: Request) { if (pending === request) pending = null }
    fun close() { closed = true; pending = null }
    fun submit(request: Request, layout: Constants.CardLayouts, active: () -> Boolean,
               apply: (Constants.CardLayouts) -> Unit): Boolean {
        if (!valid(request) || !active() || !valid(request)) return false
        val saved = try { write(layout) } catch (_: Exception) { false }
        // Check the old deadline even on failure, before touching a dialog or live appearance.
        // The guard may synchronously close/replace the request, so check identity afterwards.
        if (!active() || !valid(request)) return false
        if (!saved) { request.failed = true; return false }
        pending = null; apply(layout); return true
    }
    fun submit(
        request: Request,
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        active: () -> Boolean,
        apply: (Constants.CardLayouts, Boolean) -> Unit
    ): Boolean {
        if (!valid(request) || !active() || !valid(request)) return false
        val savedLayout = try { write(layout) } catch (_: Exception) { false }
        val savedHide = if (savedLayout) try { writeHideIssuer(hideIssuer) } catch (_: Exception) { false } else false
        if (!active() || !valid(request)) return false
        if (!savedLayout || !savedHide) { request.failed = true; return false }
        pending = null; apply(layout, hideIssuer); return true
    }
    private fun valid(request: Request) = !closed && !state.isLocked && pending === request
}
