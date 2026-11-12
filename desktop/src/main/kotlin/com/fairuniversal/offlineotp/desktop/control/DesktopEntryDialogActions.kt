package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.ui.vault.VaultDialogKind
import com.fairuniversal.offlineotp.ui.vault.VaultDialogRequest
import com.fairuniversal.offlineotp.ui.vault.VaultDialogs

/** Synchronous entry-dialog transactions with live pre/post-write session authority. */
class DesktopEntryDialogActions(private val state: DesktopVaultState, private val dialogs: VaultDialogs,
    private val save: (ArrayList<Entry>) -> Boolean) {
    private fun current(request: VaultDialogRequest) = !state.isLocked && dialogs.isCurrent(request)
    fun confirm(request: VaultDialogRequest, active: () -> Boolean, onDeleted: (Long) -> Unit): Boolean {
        if (request.kind == VaultDialogKind.PIN) return false
        return perform(request, active, {
            when (request.kind) {
                VaultDialogKind.DELETE -> state.deleteEntry(request.entryId, save)
                VaultDialogKind.COUNTER -> state.advanceCounter(request.entryId) { save(state.snapshot()) }
                VaultDialogKind.PIN -> false
            }
        }) { if (request.kind == VaultDialogKind.DELETE) onDeleted(request.entryId) }
    }
    fun submitPin(request: VaultDialogRequest, pin: String, active: () -> Boolean): Boolean {
        if (request.kind != VaultDialogKind.PIN) return false
        return perform(request, active, { state.submitPin(request.entryId, pin) { save(state.snapshot()) } }, {})
    }
    private fun perform(request: VaultDialogRequest, active: () -> Boolean, persist: () -> Boolean,
                        afterSave: () -> Unit): Boolean {
        if (!current(request) || !active() || !current(request)) return false
        val saved = try { persist() } catch (_: Exception) { false }
        // Disk changes can remain committed. Lock before updating a dialog/presenter if overdue.
        if (!active() || !current(request)) return false
        dialogs.complete(request, saved)
        if (saved && !state.isLocked) afterSave()
        return saved
    }
}
