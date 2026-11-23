package com.fairuniversal.offlineotp.desktop.control

/** A session-owned preference dialog. Persistence succeeds before the live timeout is changed. */
class DesktopAutoLockSettings(
    private val state: DesktopVaultState,
    private val read: () -> Int,
    private val write: (Int) -> Boolean,
    private val readRelockOnBackground: () -> Boolean = { false },
    private val writeRelockOnBackground: (Boolean) -> Boolean = { true }
) {
    class Request internal constructor(
        val currentSeconds: Int,
        val relockOnBackground: Boolean = false
    ) {
        var failed = false
            internal set
    }
    var pending: Request? = null
        private set
    private var closed = false
    fun open() {
        if (!closed && !state.isLocked) {
            pending = Request(read(), readRelockOnBackground())
        }
    }
    fun dismiss(request: Request) { if (pending === request) pending = null }
    fun close() { closed = true; pending = null }

    fun submit(request: Request, seconds: Int, lifetime: DesktopSessionLifetime): Boolean =
        submit(request, seconds, request.relockOnBackground, lifetime)

    fun submit(
        request: Request,
        seconds: Int,
        relockOnBackground: Boolean,
        lifetime: DesktopSessionLifetime
    ): Boolean {
        if (!valid(request) || !lifetime.checkActive() || !valid(request)) return false
        if (seconds < 0 || (seconds !in OPTIONS && seconds != request.currentSeconds)) return false
        val savedTimeout = try { write(seconds) } catch (_: Exception) { false }
        val savedBackground = if (savedTimeout) {
            try { writeRelockOnBackground(relockOnBackground) } catch (_: Exception) { false }
        } else {
            false
        }
        // Keep a persisted preference for the next session, but never revive an expired one.
        // Also check failures: an overdue timer may still be queued behind this settings write.
        if (!lifetime.checkActive() || !valid(request)) return false
        if (!savedBackground) { request.failed = true; return false }
        if (!lifetime.changeTimeout(seconds) || !valid(request)) return false
        pending = null
        return true
    }

    private fun valid(request: Request) = !closed && !state.isLocked && pending === request

    companion object { val OPTIONS = com.fairuniversal.offlineotp.ui.vault.AutoLockOptions.seconds }
}
