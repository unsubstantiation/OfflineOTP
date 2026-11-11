package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.LinScpCredentials

/** UI-thread selected-kind snapshot, then worker-owned entries for the shared LinSCP encoder. */
object DesktopLinScpExport {
    fun snapshot(state: DesktopVaultState): String {
        check(!state.isLocked)
        val candidates = state.snapshot().filter { it.isSshKey() || it.isPassword() }
        require(candidates.isNotEmpty()) { "No compatible entries" }
        return VaultJson.entriesToString(candidates)
    }
    fun seal(password: String, document: String): ByteArray {
        val entries = VaultJson.stringToEntries(document)
        try {
            require(entries.isNotEmpty() && entries.all { it.isSshKey() || it.isPassword() })
            return LinScpCredentials.exportVault(entries, password)
        } finally { entries.forEach { it.destroy() }; entries.clear() }
    }
}
