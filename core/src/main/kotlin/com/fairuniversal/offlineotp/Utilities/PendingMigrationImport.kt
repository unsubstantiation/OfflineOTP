package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.Database.Entry

/** Owns a complete Google Authenticator import until confirmation, dismissal or relock. */
class PendingMigrationImport(entries: List<Entry>, private val generation: Long) : AutoCloseable {
    private var owned: ArrayList<Entry>? = ArrayList(entries)

    private val workflow = com.fairuniversal.offlineotp.domain.InterchangeWorkflow(generation).also { check(it.preview(entries.size)) }

    /** Caller checks the live session under VaultSession.MutationLock. */
    @Synchronized
    fun take(currentGeneration: Long, sessionReady: Boolean): ArrayList<Entry>? {
        val result = owned ?: return null
        owned = null
        if (workflow.consume(currentGeneration, sessionReady)) return result
        result.forEach { it.destroy() }
        return null
    }

    @Synchronized
    override fun close() {
        workflow.cancel()
        owned?.forEach { it.destroy() }
        owned = null
    }

    override fun toString(): String = "PendingMigrationImport(redacted)"
}
