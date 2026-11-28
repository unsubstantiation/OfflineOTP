package com.fairuniversal.offlineotp.domain

/** Secret-free application decisions. Hosts serialize calls; they own decoded payloads and must
 * release them on rejection/cancel. Authority is an opaque session/setup generation, NOT a key.
 * Native adapters recheck destination identity and authority under their existing mutation lock.
 */
enum class RestorePhase { IDLE, READING, PREVIEW, CONFIRMED, INSTALLING, SUCCEEDED, FAILED, CANCELLED }
enum class RestoreFailure { READ, INVALID, EMPTY, DUPLICATE, LIMIT, STALE, STORAGE, CANCELLED }
class RestoreWorkflow {
    var phase: RestorePhase = RestorePhase.IDLE
        private set
    var failure: RestoreFailure? = null
        private set
    var count: Int = 0
        private set
    var replace: Boolean = false
        private set
    private var serial = 0L
    private var authority = 0L
    private var firstRun = false

    /** A new request invalidates every outstanding result/confirmation from its predecessor. */
    fun start(authority: Long, firstRun: Boolean): Long {
        if (serial == Long.MAX_VALUE) { cancel(); return 0 }
        serial++; this.authority = authority; this.firstRun = firstRun
        phase = RestorePhase.READING; failure = null; count = 0; replace = false
        return serial
    }
    fun decoded(ticket: Long, authority: Long, permitted: Boolean, count: Int, unique: Boolean): Boolean {
        if (!current(ticket, authority, permitted) || phase != RestorePhase.READING) return false
        if (count < 0) return reject(RestoreFailure.INVALID)
        if (count > MAX_ENTRIES) return reject(RestoreFailure.LIMIT)
        if (count == 0 && !firstRun) return reject(RestoreFailure.EMPTY)
        if (!unique) return reject(RestoreFailure.DUPLICATE)
        this.count = count; phase = RestorePhase.PREVIEW; return true
    }
    fun readFailed(ticket: Long, authority: Long, permitted: Boolean): Boolean {
        if (!current(ticket, authority, permitted) || phase != RestorePhase.READING) return false
        return reject(RestoreFailure.READ)
    }
    /** Explicit user intent only. First-run consent may precede decoding (the setup Restore button);
     * the adapter carries that intent to this transition, never a generic read callback alone. */
    fun confirm(ticket: Long, authority: Long, permitted: Boolean, replace: Boolean): Boolean {
        if (!current(ticket, authority, permitted) || phase != RestorePhase.PREVIEW) return false
        this.replace = firstRun || replace; phase = RestorePhase.CONFIRMED; return true
    }
    /** Single-use commit gate after planning against CURRENT entries, immediately before storage.
     * Cancellation after native storage has begun cannot undo that operation. */
    fun beginInstall(ticket: Long, authority: Long, permitted: Boolean, resultCount: Int): Boolean {
        if (!current(ticket, authority, permitted) || phase != RestorePhase.CONFIRMED) return false
        if (resultCount !in 0..MAX_ENTRIES) return reject(RestoreFailure.LIMIT)
        phase = RestorePhase.INSTALLING; return true
    }
    fun installed(ticket: Long, success: Boolean): Boolean {
        if (ticket != serial || phase != RestorePhase.INSTALLING) return false
        phase = if (success) RestorePhase.SUCCEEDED else RestorePhase.FAILED
        failure = if (success) null else RestoreFailure.STORAGE
        return success
    }
    fun cancel() { phase = RestorePhase.CANCELLED; failure = RestoreFailure.CANCELLED; count = 0; replace = false }
    private fun current(ticket: Long, authority: Long, permitted: Boolean): Boolean {
        if (ticket == 0L || ticket != serial) return false
        if (!permitted || authority != this.authority) { cancel(); failure = RestoreFailure.STALE; return false }
        return true
    }
    private fun reject(reason: RestoreFailure): Boolean { failure = reason; phase = RestorePhase.FAILED; count = 0; return false }
    override fun toString() = "RestoreWorkflow(redacted)"
    companion object { const val MAX_ENTRIES = 2000 }
}

/** Identity comparison remains at the entry adapter; common code sees only equality outcomes,
 * never seeds or native Entry objects. Plan against the CURRENT vault at confirmation time. */
class RestoreMergeDecision internal constructor(importedIndices: List<Int>, val retainCurrent: Boolean,
                                                val resultCount: Int, val failure: RestoreFailure?) {
    private val selected = importedIndices.toList()
    val importedIndices: List<Int> get() = selected.toList()
}
class RestoreMergePolicy {
    fun plan(matchesCurrent: List<Boolean>, currentCount: Int, importedUnique: Boolean,
             currentUnique: Boolean, replace: Boolean): RestoreMergeDecision {
        fun rejected(reason: RestoreFailure) = RestoreMergeDecision(emptyList(), !replace, 0, reason)
        if (matchesCurrent.size > RestoreWorkflow.MAX_ENTRIES || currentCount !in 0..RestoreWorkflow.MAX_ENTRIES) return rejected(RestoreFailure.LIMIT)
        if (!importedUnique || (!replace && !currentUnique)) return rejected(RestoreFailure.DUPLICATE)
        val keep = matchesCurrent.indices.filter { replace || !matchesCurrent[it] }
        val total = keep.size + if (replace) 0 else currentCount
        if (total > RestoreWorkflow.MAX_ENTRIES) return rejected(RestoreFailure.LIMIT)
        return RestoreMergeDecision(keep, !replace, total, null)
    }
}
