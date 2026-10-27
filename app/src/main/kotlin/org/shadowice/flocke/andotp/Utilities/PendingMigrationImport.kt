package org.shadowice.flocke.andotp.Utilities

import org.shadowice.flocke.andotp.Database.Entry

/** Owns a complete Google Authenticator import until confirmation, dismissal or relock. */
internal class PendingMigrationImport(entries: List<Entry>, private val generation: Long) : AutoCloseable {
    private var owned: ArrayList<Entry>? = ArrayList(entries)

    /** Caller checks the live session under VaultSession.MutationLock. */
    @Synchronized
    fun take(currentGeneration: Long, sessionReady: Boolean): ArrayList<Entry>? {
        val result = owned ?: return null
        owned = null
        if (sessionReady && generation == currentGeneration) return result
        result.forEach { it.destroy() }
        return null
    }

    @Synchronized
    override fun close() {
        owned?.forEach { it.destroy() }
        owned = null
    }

    override fun toString(): String = "PendingMigrationImport(redacted)"
}
