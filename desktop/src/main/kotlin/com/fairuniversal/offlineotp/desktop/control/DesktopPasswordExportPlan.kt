package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.PasswordCsvExport
import com.fairuniversal.offlineotp.Utilities.ThunderbirdCsv
import java.util.Locale

/**
 * UI-thread preparation for confirmed browser export. No writes or UI authority here.
 * Never converts unlike headers: canonical conversion can discard provider-specific columns.
 * Owns mutable CSV snapshots until taken by a writer or closed. Do not log payloads.
 */
class DesktopPasswordExportPlan private constructor(
    val groups: List<Group>,
    val foreignPasswords: Int,
    val archives: Int
) : AutoCloseable {
    class Group internal constructor(
        val dialect: PasswordCsvExport.Dialect,
        val count: Int,
        private var bytes: ByteArray?
    ) {
        /** Transfers ownership once. Caller must wipe the returned plaintext after use. */
        @Synchronized fun takeBytes(): ByteArray? = bytes.also { bytes = null }
        @Synchronized internal fun clear() { bytes?.fill(0); bytes = null }
        override fun toString() = "BrowserExportGroup($dialect, count=$count, redacted)"
    }
    val count: Int get() = groups.sumOf { it.count }
    override fun close() { groups.forEach { it.clear() } }
    override fun toString() = "DesktopPasswordExportPlan(count=$count, groups=${groups.size}, redacted)"

    companion object {
        /** All live entries, independent of display filters and reveal state. No entries retained. */
        fun prepare(state: DesktopVaultState): DesktopPasswordExportPlan {
            check(!state.isLocked)
            return prepareEntries(state.snapshot())
        }

        internal fun prepareEntries(entries: List<Entry>): DesktopPasswordExportPlan {
            // Linked map keeps first-seen group and vault row order deterministic.
            val grouped = linkedMapOf<Pair<PasswordCsvExport.Dialect, List<String>>, MutableList<Entry>>()
            var foreign = 0
            var archives = 0
            entries.forEach { entry ->
                if (entry.isPasswordArchive()) archives++
                when (val support = PasswordCsvExport.support(entry)) {
                    is PasswordCsvExport.Support.Exportable -> {
                        val columns = entry.getPasswordFields().columns().map { it.trim().lowercase(Locale.ROOT) }
                        grouped.getOrPut(support.dialect to columns) { arrayListOf() }.add(entry)
                    }
                    PasswordCsvExport.Support.Foreign -> foreign++
                    PasswordCsvExport.Support.NotPassword -> Unit
                }
            }
            val prepared = arrayListOf<Group>()
            try {
                grouped.forEach { (key, rows) ->
                    val fields = rows.map { it.getPasswordFields() }
                    val columns = fields.first().columns()
                    // Exact source cells, not browser-conversion aliases: blank name/note cells
                    // must not be filled from a second title/notes column in the same source.
                    val bytes = ThunderbirdCsv.exportCsv(columns, fields.map { row -> columns.map { row.value(it) } })
                    prepared.add(Group(key.first, rows.size, bytes))
                }
                return DesktopPasswordExportPlan(prepared.toList(), foreign, archives)
            } catch (error: Exception) {
                prepared.forEach { it.clear() }
                throw error
            } finally { grouped.clear() }
        }
    }
}
