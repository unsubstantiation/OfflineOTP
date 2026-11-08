package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput

/** Identity-bound add dialog. Owns requests, never form secrets; called only on the UI thread. */
class DesktopEntryCreation(private val state: DesktopVaultState, private val save: (Entry) -> Boolean) {
    enum class Failure { INVALID_INPUT, SAVE_FAILED }
    class Request internal constructor() {
        var failure: Failure? = null
            internal set
    }
    var pending: Request? = null
        private set
    private var closed = false

    fun open() {
        if (!closed && !state.isLocked) pending = Request()
    }
    fun dismiss(request: Request) { if (pending === request) pending = null }
    fun close() { closed = true; pending = null }

    fun submit(request: Request, input: ManualEntryInput): Boolean {
        if (closed || state.isLocked || pending !== request) return false
        val draft = try { input.build() } catch (_: IllegalArgumentException) {
            request.failure = Failure.INVALID_INPUT
            return false
        }
        try {
            if (save(draft)) {
                pending = null
                return true
            }
            request.failure = Failure.SAVE_FAILED
            return false
        } catch (_: Exception) {
            request.failure = Failure.SAVE_FAILED
            return false
        } finally {
            // EntryEditor transfers ownership only after successful persistence/publication.
            if (state.snapshot().none { it === draft }) draft.destroy()
        }
    }
}
