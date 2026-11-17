package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.LinScpCredentials

/** UI-thread selected-kind snapshot, then worker-owned entries for the shared LinSCP encoder. */
object DesktopLinScpExport {
    fun snapshot(state: DesktopVaultState, selectedIds: Set<Long>? = null): String {
        check(!state.isLocked)
        val candidates = state.snapshot().filter { it.isSshKey() || it.isPassword() }
        val selected = if (selectedIds == null) candidates else candidates.filter { it.getListId() in selectedIds }
        require(selectedIds == null || selected.size == selectedIds.size)
        require(selected.isNotEmpty()) { "No compatible entries" }
        return VaultJson.entriesToString(selected)
    }
    fun plain(document: String): ByteArray {
        val entries = VaultJson.stringToEntries(document)
        try {
            require(entries.isNotEmpty() && entries.all { it.isSshKey() || it.isPassword() })
            return LinScpCredentials.exportPlain(entries)
        } finally { entries.forEach { it.destroy() }; entries.clear() }
    }
    fun seal(password: String, document: String): ByteArray {
        val entries = VaultJson.stringToEntries(document)
        try {
            require(entries.isNotEmpty() && entries.all { it.isSshKey() || it.isPassword() })
            return LinScpCredentials.exportVault(entries, password)
        } finally { entries.forEach { it.destroy() }; entries.clear() }
    }
}
