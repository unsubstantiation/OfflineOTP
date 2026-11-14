package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.EmailMaterial

/** Read-only, exact-entry authority. Never retains rendered credential strings. UI-thread only. */
class DesktopEmailDetails(private val state: DesktopVaultState) {
    class Request internal constructor(internal val target: Entry) {
        val entryId: Long get() = target.getListId()
        var secretsVisible = false
            internal set
    }
    var pending: Request? = null
        private set
    private var closed = false

    fun open(id: Long, revealed: Boolean) {
        pending = null
        if (closed || state.isLocked || !revealed) return
        val entry = state.snapshot().firstOrNull { it.getListId() == id && it.isEmail() } ?: return
        pending = Request(entry)
    }
    private fun valid(request: Request, revealed: Boolean): Boolean {
        val valid = !closed && !state.isLocked && pending === request && revealed &&
            state.snapshot().any { it === request.target }
        if (!valid && pending === request) dismiss(request)
        return valid
    }
    fun rows(request: Request, revealed: Boolean): List<EmailMaterial.Detail>? =
        if (valid(request, revealed)) EmailAccounts.details(request.target, request.secretsVisible) else null

    fun toggleSecrets(request: Request, revealed: Boolean): Boolean {
        if (!valid(request, revealed)) return false
        request.secretsVisible = !request.secretsVisible
        return true
    }
    fun dismiss(request: Request) {
        if (pending !== request) return
        request.secretsVisible = false; pending = null
    }
    fun close() { pending?.let(::dismiss); closed = true }
}
