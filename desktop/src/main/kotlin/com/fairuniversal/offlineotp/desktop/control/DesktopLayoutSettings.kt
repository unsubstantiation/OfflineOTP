package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.Constants

/** Save before applying to the presenter; stale/expired requests cannot change the current view. */
class DesktopLayoutSettings(
    private val state: DesktopVaultState,
    private val read: () -> Constants.CardLayouts,
    private val write: (Constants.CardLayouts) -> Boolean,
    private val readHideIssuer: () -> Boolean = { false },
    private val writeHideIssuer: (Boolean) -> Boolean = { true },
    private val readShowPreviousToken: () -> Boolean = { true },
    private val writeShowPreviousToken: (Boolean) -> Boolean = { true },
    private val readTokenGroupSize: () -> Int? = { null },
    private val writeTokenGroupSize: (Int?) -> Boolean = { true },
    private val readShowIndividualTimeouts: () -> Boolean = { true },
    private val writeShowIndividualTimeouts: (Boolean) -> Boolean = { true }
) {
    class Request internal constructor(
        val current: Constants.CardLayouts,
        val hideIssuer: Boolean = false,
        val showPreviousToken: Boolean = true,
        val tokenGroupSize: Int? = null,
        val showIndividualTimeouts: Boolean = true
    ) {
        var failed = false
            internal set
    }
    var pending: Request? = null
        private set
    private var closed = false
    fun open() {
        if (!closed && !state.isLocked) {
            pending = Request(
                read(),
                readHideIssuer(),
                readShowPreviousToken(),
                readTokenGroupSize(),
                readShowIndividualTimeouts()
            )
        }
    }
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
    fun submit(
        request: Request,
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?,
        active: () -> Boolean,
        apply: (Constants.CardLayouts, Boolean, Boolean, Int?) -> Unit
    ): Boolean {
        if (!valid(request) || !active() || !valid(request)) return false
        val savedLayout = try { write(layout) } catch (_: Exception) { false }
        val savedHide = if (savedLayout) try { writeHideIssuer(hideIssuer) } catch (_: Exception) { false } else false
        val savedPrev = if (savedHide) try { writeShowPreviousToken(showPreviousToken) } catch (_: Exception) { false } else false
        val savedGroup = if (savedPrev) try { writeTokenGroupSize(tokenGroupSize) } catch (_: Exception) { false } else false
        if (!active() || !valid(request)) return false
        if (!savedGroup) { request.failed = true; return false }
        pending = null; apply(layout, hideIssuer, showPreviousToken, tokenGroupSize); return true
    }
    fun submit(
        request: Request,
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?,
        showIndividualTimeouts: Boolean,
        active: () -> Boolean,
        apply: (Constants.CardLayouts, Boolean, Boolean, Int?, Boolean) -> Unit
    ): Boolean {
        if (!valid(request) || !active() || !valid(request)) return false
        val savedLayout = try { write(layout) } catch (_: Exception) { false }
        val savedHide = if (savedLayout) try { writeHideIssuer(hideIssuer) } catch (_: Exception) { false } else false
        val savedPrev = if (savedHide) try { writeShowPreviousToken(showPreviousToken) } catch (_: Exception) { false } else false
        val savedGroup = if (savedPrev) try { writeTokenGroupSize(tokenGroupSize) } catch (_: Exception) { false } else false
        val savedTimeouts = if (savedGroup) try { writeShowIndividualTimeouts(showIndividualTimeouts) } catch (_: Exception) { false } else false
        if (!active() || !valid(request)) return false
        if (!savedTimeouts) { request.failed = true; return false }
        pending = null; apply(layout, hideIssuer, showPreviousToken, tokenGroupSize, showIndividualTimeouts); return true
    }
    private fun valid(request: Request) = !closed && !state.isLocked && pending === request
}
