package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryDraft
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput

/** UI-thread edit authority: exact dialog and entry identity, not a reusable id or list position. */
class DesktopEntryEditing(private val state: DesktopVaultState, private val replace: (Entry, Entry) -> Boolean) {
    enum class Failure { INVALID_INPUT, SAVE_FAILED }
    class Request internal constructor(internal val target: Entry) {
        val entryId: Long get() = target.getListId()
        var failure: Failure? = null
            internal set
    }
    var pending: Request? = null
        private set
    private var closed = false

    fun open(id: Long) {
        if (closed || state.isLocked) return
        val target = state.snapshot().firstOrNull { it.getListId() == id } ?: return
        if (target.getType() !in EntryDraft.offerableTypes()) return
        pending = Request(target)
    }
    private fun current(request: Request): Boolean = !closed && !state.isLocked && pending === request &&
        state.snapshot().any { it === request.target }
    fun initialInput(request: Request): ManualEntryInput? =
        if (current(request)) ManualEntryInput.fromEntry(request.target) else null
    fun dismiss(request: Request) { if (pending === request) pending = null }
    fun close() { closed = true; pending = null }

    /** [revealed] is checked against the presenter's live reveal set, never the opening frame. */
    fun submit(request: Request, input: ManualEntryInput, revealed: Boolean): Boolean {
        if (!revealed || !current(request)) return false
        val replacement = try { input.build(request.target) } catch (_: IllegalArgumentException) {
            request.failure = Failure.INVALID_INPUT
            return false
        }
        try {
            if (replace(request.target, replacement)) {
                pending = null
                return true
            }
            request.failure = Failure.SAVE_FAILED
            return false
        } catch (_: Exception) {
            request.failure = Failure.SAVE_FAILED
            return false
        } finally {
            if (state.snapshot().none { it === replacement }) replacement.destroy()
        }
    }
}
