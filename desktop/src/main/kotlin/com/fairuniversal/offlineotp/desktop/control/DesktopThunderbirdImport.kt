package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.ThunderbirdCsv
import com.fairuniversal.offlineotp.Utilities.ThunderbirdImport
import java.io.File
import java.util.concurrent.Executor

/**
 * Thunderbird / Firefox `Export Logins` CSV importer for the desktop client.
 *
 * Mirrors Android's `chooseThunderbirdImport` and iOS's `importThunderbirdBackup`: parses rows
 * into unified `EMAIL` entries on a background worker, shows the candidate count for explicit
 * confirmation on the UI thread, merges into the vault via [EntryEditor.importEntries], and scrubs
 * the raw CSV bytes immediately after parsing.
 */
class DesktopThunderbirdImport(
    state: DesktopVaultState,
    worker: Executor,
    dispatch: (Runnable) -> Unit,
    changed: () -> Unit,
    install: (List<Entry>) -> Int?,
    decode: (File, Unit) -> ArrayList<Entry> = ::readThunderbird
) : DesktopFileImport<Unit>(state, worker, dispatch, changed, install, decode) {

    /** Opens [source] and immediately begins background CSV parsing so the preview count is ready. */
    fun openAndRead(source: File, active: () -> Boolean) {
        open(source)
        val request = pending ?: return
        submit(request, Unit, active)
    }

    companion object {
        private fun readThunderbird(source: File, @Suppress("UNUSED_PARAMETER") unused: Unit): ArrayList<Entry> {
            val bytes = source.inputStream().use { BoundedInputReader.read(it, ThunderbirdCsv.MAX_FILE_BYTES) }
            return try {
                ThunderbirdImport.prepare(bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }
}
