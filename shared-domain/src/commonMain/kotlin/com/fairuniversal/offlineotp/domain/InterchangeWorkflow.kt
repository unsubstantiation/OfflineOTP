package com.fairuniversal.offlineotp.domain

enum class InterchangePhase { PREPARING, PREVIEW, CONSUMED, CANCELED }
/** One preview/confirmation or export delivery, bound to an unlocked generation.
 * Native owners keep the exact pending material; count is not a document identity substitute.
 */
class InterchangeWorkflow(private val generation: Long) {
    var phase = InterchangePhase.PREPARING
        private set
    fun preview(count: Int): Boolean {
        if (phase != InterchangePhase.PREPARING || count !in 0..EntryDocumentCodec.MAX_ENTRIES) return false
        phase = InterchangePhase.PREVIEW; return true
    }
    fun consume(currentGeneration: Long, sessionReady: Boolean): Boolean {
        if (phase != InterchangePhase.PREVIEW) return false
        phase = InterchangePhase.CONSUMED
        return sessionReady && currentGeneration == generation
    }
    fun current(currentGeneration: Long, sessionReady: Boolean): Boolean =
        phase == InterchangePhase.CONSUMED && sessionReady && currentGeneration == generation
    fun cancel() { phase = InterchangePhase.CANCELED }
    override fun toString() = "InterchangeWorkflow(redacted)"
}

enum class PortableBatchStatus { ADDED, COMPLETE, DUPLICATE, DIFFERENT_EXPORT, TOO_MANY, STALE }
/** Only batch identity/counts are shared; adapters own seeds and ordered payload transfer. */
class MigrationBatchPolicy(private val generation: Long) {
    private val indices = mutableSetOf<Int>()
    private var version = -1
    private var batchId = 0
    var expected = 0
        private set
    private var total = 0
    var closed = false
        private set
    val received: Int get() = indices.size
    fun add(version: Int, size: Int, index: Int, id: Int, count: Int,
            currentGeneration: Long, sessionReady: Boolean): PortableBatchStatus {
        if (closed || !sessionReady || currentGeneration != generation) { cancel(); return PortableBatchStatus.STALE }
        if (size !in 1..EntryDocumentCodec.MAX_ENTRIES || index !in 0 until size || count !in 1..EntryDocumentCodec.MAX_ENTRIES) {
            cancel(); return PortableBatchStatus.TOO_MANY
        }
        if (indices.isEmpty()) { this.version = version; batchId = id; expected = size }
        else if (this.version != version || batchId != id || expected != size) return PortableBatchStatus.DIFFERENT_EXPORT
        if (index in indices) return PortableBatchStatus.DUPLICATE
        if (count > EntryDocumentCodec.MAX_ENTRIES - total) { cancel(); return PortableBatchStatus.TOO_MANY }
        indices.add(index); total += count
        return if (indices.size == expected) PortableBatchStatus.COMPLETE else PortableBatchStatus.ADDED
    }
    fun take(currentGeneration: Long, sessionReady: Boolean): Boolean {
        val permitted = !closed && sessionReady && currentGeneration == generation && indices.isNotEmpty() && indices.size == expected
        cancel(); return permitted
    }
    fun cancel() { closed = true; indices.clear(); total = 0 }
    override fun toString() = "MigrationBatchPolicy(redacted)"
}
