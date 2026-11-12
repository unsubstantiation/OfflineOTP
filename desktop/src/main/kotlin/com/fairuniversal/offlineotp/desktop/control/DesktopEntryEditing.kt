package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryDraft
import com.fairuniversal.offlineotp.ui.vault.EmailSettingsInput
import com.fairuniversal.offlineotp.ui.vault.EmailPasswordInput
import com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput

/** UI-thread edit authority: exact dialog and entry identity, not a reusable id or list position. */
class DesktopEntryEditing(private val state: DesktopVaultState, private val replace: (Entry, Entry) -> Boolean) {
    enum class Failure { INVALID_INPUT, SAVE_FAILED }
    class Request internal constructor(internal val target: Entry, val emailSettings: Boolean = false) {
        val isEmail: Boolean get() = target.isEmail()
        val isPassword: Boolean get() = target.isPassword()
        val entryId: Long get() = target.getListId()
        var failure: Failure? = null
            internal set
    }
    var pending: Request? = null
        private set
    private var closed = false

    fun open(id: Long, emailSettings: Boolean = false) {
        if (closed || state.isLocked) return
        val target = state.snapshot().firstOrNull { it.getListId() == id } ?: return
        if (target.isPassword() && !PasswordEntryInput.supports(target)) return
        if (!target.isEmail() && !target.isPassword() && target.getType() !in EntryDraft.offerableTypes()) return
        if (emailSettings && !target.isEmail()) return
        pending = Request(target, emailSettings)
    }
    private fun current(request: Request): Boolean = !closed && !state.isLocked && pending === request &&
        state.snapshot().any { it === request.target }
    fun initialInput(request: Request): ManualEntryInput? =
        if (current(request) && !request.isPassword && !request.isEmail) ManualEntryInput.fromEntry(request.target) else null
    fun initialPasswordInput(request: Request): PasswordEntryInput? =
        if (current(request) && request.isPassword) PasswordEntryInput.fromEntry(request.target) else null

    fun initialEmailSettings(request: Request): EmailSettingsInput? =
        if (current(request) && request.isEmail && request.emailSettings) EmailSettingsInput.fromEntry(request.target) else null

    fun submitEmailSettings(request: Request, input: EmailSettingsInput, revealed: Boolean, active: () -> Boolean = { true }): Boolean {
        if (!revealed || !current(request) || !active() || !current(request) || !request.isEmail || !request.emailSettings) return false
        val replacement = try { input.build(request.target) } catch (_: IllegalArgumentException) {
            request.failure = Failure.INVALID_INPUT; return false
        }
        return persist(request, replacement, active)
    }

    fun initialEmailPasswords(request: Request): EmailPasswordInput? =
        if (current(request) && request.isEmail && !request.emailSettings) EmailPasswordInput.fromEntry(request.target) else null

    fun submitEmailPasswords(request: Request, input: EmailPasswordInput, revealed: Boolean, active: () -> Boolean = { true }): Boolean {
        if (!revealed || !current(request) || !active() || !current(request) || !request.isEmail || request.emailSettings) return false
        val replacement = try { input.build(request.target) } catch (_: IllegalArgumentException) {
            request.failure = Failure.INVALID_INPUT
            return false
        }
        return persist(request, replacement, active)
    }

    fun submitPassword(request: Request, input: PasswordEntryInput, revealed: Boolean, active: () -> Boolean = { true }): Boolean {
        if (!revealed || !current(request) || !active() || !current(request) || !request.isPassword) return false
        val replacement = try { input.build(request.target) } catch (_: IllegalArgumentException) {
            request.failure = Failure.INVALID_INPUT
            return false
        }
        return persist(request, replacement, active)
    }
    fun dismiss(request: Request) { if (pending === request) pending = null }
    fun close() { closed = true; pending = null }

    /** [revealed] is checked against the presenter's live reveal set, never the opening frame. */
    fun submit(request: Request, input: ManualEntryInput, revealed: Boolean, active: () -> Boolean = { true }): Boolean {
        if (!revealed || !current(request) || !active() || !current(request) || request.isPassword || request.isEmail) return false
        val replacement = try { input.build(request.target) } catch (_: IllegalArgumentException) {
            request.failure = Failure.INVALID_INPUT
            return false
        }
        return persist(request, replacement, active)
    }

    private fun requestActive(request: Request) = !closed && !state.isLocked && pending === request

    private fun persist(request: Request, replacement: Entry, active: () -> Boolean): Boolean {
        try {
            if (!active() || !current(request)) return false
            val saved = replace(request.target, replacement)
            // On success the original is no longer in state. Check request identity instead.
            if (!active() || !requestActive(request)) return false
            if (saved) {
                pending = null
                return true
            }
            request.failure = Failure.SAVE_FAILED
            return false
        } catch (_: Exception) {
            if (active() && requestActive(request)) request.failure = Failure.SAVE_FAILED
            return false
        } finally {
            if (state.snapshot().none { it === replacement }) replacement.destroy()
        }
    }
}
