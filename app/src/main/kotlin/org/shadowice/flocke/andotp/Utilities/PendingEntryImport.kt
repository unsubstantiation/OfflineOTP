package org.shadowice.flocke.andotp.Utilities

import org.shadowice.flocke.andotp.Database.Entry

/** Owns a parsed entry until a confirmation consumes it or the dialog is dismissed. */
internal class PendingEntryImport(entry: Entry, private val generation: Long) : AutoCloseable {
    private var pending: Entry? = entry

    /** Caller must check the live session and call this under VaultSession.MutationLock. */
    @Synchronized
    fun take(currentGeneration: Long, sessionReady: Boolean): Entry? {
        val entry = pending ?: return null
        pending = null
        if (sessionReady && currentGeneration == generation) return entry
        entry.destroy()
        return null
    }

    @Synchronized
    override fun close() {
        pending?.destroy()
        pending = null
    }

    override fun toString(): String = "PendingEntryImport(redacted)"
}
