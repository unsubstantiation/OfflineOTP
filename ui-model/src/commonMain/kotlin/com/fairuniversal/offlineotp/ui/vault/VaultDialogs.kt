package com.fairuniversal.offlineotp.ui.vault

/** Only identity and action kind cross the dialog boundary; never a card, entry or PIN. */
enum class VaultDialogKind { PIN, COUNTER, DELETE }

class VaultDialogRequest(val kind: VaultDialogKind, val entryId: Long, val failed: Boolean = false) {
    override fun toString(): String = "VaultDialogRequest(kind=$kind, failed=$failed)"
}

/**
 * One pending dialog per session. Request identity rejects callbacks from dismissed/replaced
 * dialogs, including a newly opened dialog for the same entry. Hosts must check [isCurrent]
 * before performing a write, serialize callbacks on the UI thread, and [clear] on session lock.
 */
class VaultDialogs(private val onChange: () -> Unit = {}) {
    var pending: VaultDialogRequest? = null
        private set

    fun open(kind: VaultDialogKind, entryId: Long) {
        pending = VaultDialogRequest(kind, entryId)
        onChange()
    }

    fun isCurrent(request: VaultDialogRequest): Boolean = pending === request

    fun dismiss(request: VaultDialogRequest) {
        if (isCurrent(request)) clear()
    }

    fun complete(request: VaultDialogRequest, saved: Boolean) {
        if (!isCurrent(request)) return
        pending = if (saved) null else VaultDialogRequest(request.kind, request.entryId, failed = true)
        onChange()
    }

    fun clear() {
        pending = null
        onChange()
    }
}

/** Hosts can supply localized wording without reimplementing dialog selection or retry states. */
data class VaultDialogStrings(
    val cancel: String = "Cancel",
    val save: String = "Save",
    val pinLabel: String = "PIN",
    val pinTitle: String = "Enter PIN",
    val pinFailure: String = "The PIN could not be saved. Re-enter it to try again.",
    val counterTitle: String = "Advance counter?",
    val counterMessage: String = "Generate the next code only when the service asks for it. Advancing unnecessarily can put this account out of sync.",
    val counterConfirm: String = "Next code",
    val counterFailure: String = "The counter could not be saved. It has not been advanced.",
    val deleteTitle: String = "Delete account?",
    val deleteMessage: String = "Remove this account from the vault? This cannot be undone. Make sure you have another way to sign in.",
    val deleteConfirm: String = "Delete",
    val deleteFailure: String = "The account could not be deleted. No entry was removed."
)
