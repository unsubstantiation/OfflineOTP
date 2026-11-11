package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.ui.vault.EmailEntryInput
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

    fun submit(request: Request, input: ManualEntryInput, active: () -> Boolean = { true }): Boolean =
        submitDraft(request, active) { input.build() }
    fun submitEmail(request: Request, input: EmailEntryInput, active: () -> Boolean = { true }): Boolean =
        submitDraft(request, active) { input.build() }

    private fun current(request: Request) = !closed && !state.isLocked && pending === request

    private fun submitDraft(request: Request, active: () -> Boolean, build: () -> Entry): Boolean {
        if (!current(request) || !active() || !current(request)) return false
        val draft = try { build() } catch (_: IllegalArgumentException) {
            request.failure = Failure.INVALID_INPUT
            return false
        }
        try {
            if (!active() || !current(request)) return false
            val saved = save(draft)
            // Persistence may have crossed the idle deadline or reentered the host.
            // A completed disk write is not rolled back; only UI completion is suppressed.
            if (!active() || !current(request)) return false
            if (saved) {
                pending = null
                return true
            }
            request.failure = Failure.SAVE_FAILED
            return false
        } catch (_: Exception) {
            if (active() && current(request)) request.failure = Failure.SAVE_FAILED
            return false
        } finally {
            // EntryEditor transfers ownership only after successful persistence/publication.
            if (state.snapshot().none { it === draft }) draft.destroy()
        }
    }
}
