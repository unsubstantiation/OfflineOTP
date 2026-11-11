package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryDraft
import com.fairuniversal.offlineotp.ui.vault.EmailPasswordInput
import com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput

/** UI-thread edit authority: exact dialog and entry identity, not a reusable id or list position. */
class DesktopEntryEditing(private val state: DesktopVaultState, private val replace: (Entry, Entry) -> Boolean) {
    enum class Failure { INVALID_INPUT, SAVE_FAILED }
    class Request internal constructor(internal val target: Entry) {
        val isEmail: Boolean get() = target.isEmail()
        val isPassword: Boolean get() = target.isPassword()
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
        if (target.isPassword() && !PasswordEntryInput.supports(target)) return
        if (!target.isEmail() && !target.isPassword() && target.getType() !in EntryDraft.offerableTypes()) return
        pending = Request(target)
    }
    private fun current(request: Request): Boolean = !closed && !state.isLocked && pending === request &&
        state.snapshot().any { it === request.target }
    fun initialInput(request: Request): ManualEntryInput? =
        if (current(request) && !request.isPassword && !request.isEmail) ManualEntryInput.fromEntry(request.target) else null
    fun initialPasswordInput(request: Request): PasswordEntryInput? =
        if (current(request) && request.isPassword) PasswordEntryInput.fromEntry(request.target) else null

    fun initialEmailPasswords(request: Request): EmailPasswordInput? =
        if (current(request) && request.isEmail) EmailPasswordInput.fromEntry(request.target) else null

    fun submitEmailPasswords(request: Request, input: EmailPasswordInput, revealed: Boolean): Boolean {
        if (!revealed || !current(request) || !request.isEmail) return false
        val replacement = try { input.build(request.target) } catch (_: IllegalArgumentException) {
            request.failure = Failure.INVALID_INPUT
            return false
        }
        return persist(request, replacement)
    }

    fun submitPassword(request: Request, input: PasswordEntryInput, revealed: Boolean): Boolean {
        if (!revealed || !current(request) || !request.isPassword) return false
        val replacement = try { input.build(request.target) } catch (_: IllegalArgumentException) {
            request.failure = Failure.INVALID_INPUT
            return false
        }
        return persist(request, replacement)
    }
    fun dismiss(request: Request) { if (pending === request) pending = null }
    fun close() { closed = true; pending = null }

    /** [revealed] is checked against the presenter's live reveal set, never the opening frame. */
    fun submit(request: Request, input: ManualEntryInput, revealed: Boolean): Boolean {
        if (!revealed || !current(request) || request.isPassword || request.isEmail) return false
        val replacement = try { input.build(request.target) } catch (_: IllegalArgumentException) {
            request.failure = Failure.INVALID_INPUT
            return false
        }
        return persist(request, replacement)
    }

    private fun persist(request: Request, replacement: Entry): Boolean {
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
