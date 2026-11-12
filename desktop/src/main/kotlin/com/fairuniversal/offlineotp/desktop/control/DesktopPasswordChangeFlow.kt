package com.fairuniversal.offlineotp.desktop.control

import java.util.concurrent.Executor

/** Window-level interlock: no fresh unlock or normal window exit until rekey delivery completes. */
class DesktopPasswordChangeFlow(private val worker: Executor, private val dispatch: (Runnable) -> Unit,
    private val change: (String, String) -> VaultController.Session, private val changed: () -> Unit) : AutoCloseable {
    var operation: DesktopPasswordChange? = null
        private set
    private var active: (() -> Boolean)? = null
    private var closed = false
    val canUnlock: Boolean get() = !closed && operation == null
    val canCloseWindow: Boolean get() = operation?.status != DesktopPasswordChange.Status.WORKING
    fun open(state: DesktopVaultState, checkActive: () -> Boolean, lock: () -> Unit): Boolean {
        if (closed || operation != null || state.isLocked || !checkActive() || state.isLocked || closed || operation != null) return false
        active = checkActive
        operation = DesktopPasswordChange(state, worker, dispatch, lock, change, changed)
        changed(); return true
    }
    fun submit(expected: DesktopPasswordChange, current: String, next: String, confirmation: String): Boolean {
        if (closed || operation !== expected) return false
        return expected.submit(current, next, confirmation) { active?.invoke() == true }
    }
    fun dismiss(expected: DesktopPasswordChange) {
        if (operation !== expected || !canCloseWindow) return
        expected.close(); operation = null; active = null; changed()
    }
    /** Auto-lock dismisses only an unsubmitted form, not a rekey that deliberately locked first. */
    fun sessionLocked() {
        val owner = operation ?: return
        if (owner.status == DesktopPasswordChange.Status.INPUT || owner.status == DesktopPasswordChange.Status.INVALID_INPUT) dismiss(owner)
    }
    override fun close() {
        closed = true; operation?.close(); operation = null; active = null
    }
}
