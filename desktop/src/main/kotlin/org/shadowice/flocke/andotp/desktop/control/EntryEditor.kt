package org.shadowice.flocke.andotp.desktop.control

import org.shadowice.flocke.andotp.Database.Entry

/**
 * Applies entry mutations to the unlocked [DesktopVaultState] and persists each successful one
 * through the [VaultController]. All methods fail closed: a rejected mutation never triggers a
 * vault write, and a failed persistence is reported rather than silently dropped.
 */
class EntryEditor(
    private val controller: VaultController,
    private val state: DesktopVaultState
) {
    fun add(entry: Entry): Boolean {
        if (!state.add(entry)) return false
        return persist()
    }

    fun replace(existing: Entry, replacement: Entry): Boolean {
        if (!state.replace(existing, replacement)) return false
        return persist()
    }

    fun removeAt(position: Int): Boolean {
        state.removeAt(position) ?: return false
        return persist()
    }

    private fun persist(): Boolean = controller.save(state.key, state.snapshot())
}
