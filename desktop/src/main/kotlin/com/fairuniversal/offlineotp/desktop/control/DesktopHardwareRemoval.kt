package com.fairuniversal.offlineotp.desktop.control

import java.util.concurrent.Executor

/** Confirmation-bound local enrollment removal; never contacts or resets a physical key. */
class DesktopHardwareRemoval(private val state: DesktopVaultState, private val worker: Executor,
    private val dispatch: (Runnable) -> Unit, private val remove: () -> Unit, private val changed: () -> Unit) {
    enum class Status { CONFIRM, REMOVING, REMOVED, FAILED }
    class Request internal constructor() {
        var status = Status.CONFIRM
            internal set
        @Volatile internal var canceled = false
    }
    var pending: Request? = null
        private set
    private var closed = false
    private fun current(request: Request) = !closed && !state.isLocked && pending === request
    fun open() { if (!closed && !state.isLocked && pending == null) { pending = Request(); changed() } }
    fun dismiss(request: Request) {
        if (pending !== request) return
        request.canceled = true; pending = null; changed()
    }
    fun close() { closed = true; pending?.let(::dismiss) }
    fun confirm(request: Request, active: () -> Boolean): Boolean {
        if (!current(request) || request.status != Status.CONFIRM || !active() || !current(request)) return false
        request.status = Status.REMOVING; changed()
        try {
            worker.execute {
                if (request.canceled || state.isLocked) return@execute
                // Once deletion starts it may complete despite cancellation. Never restore an old record.
                val result = try { remove(); Status.REMOVED } catch (_: Exception) { Status.FAILED }
                try { dispatch(Runnable {
                    if (current(request) && active() && current(request)) { request.status = result; changed() }
                }) } catch (_: Exception) { request.canceled = true }
            }
        } catch (_: Exception) { if (current(request)) { request.status = Status.FAILED; changed() } }
        return true
    }
}
