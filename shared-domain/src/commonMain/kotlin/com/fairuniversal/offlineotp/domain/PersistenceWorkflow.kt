package com.fairuniversal.offlineotp.domain

/** No bytes, credentials, filesystem handles or settings cross this policy boundary.
 * Native owners serialize events and report only completed, verified atomic operations.
 * Metadata transactions stage ciphertext before changing settings; ambiguous commits quarantine
 * the transaction until restart/recovery. A backup is never removed by this workflow.
 */
enum class PersistenceReadFailure { MISSING, MISSING_KEY, AUTHENTICATION_FAILED, CORRUPT, IO_FAILURE, RECOVERY_REQUIRED }
enum class PersistenceWriteFailure { MISSING, MISSING_KEY, AUTHENTICATION_FAILED, CORRUPT, IO_FAILURE, INVALID_INPUT, ALREADY_EXISTS, BACKUP_FAILED, CANCELED }
enum class PersistenceCommitState { UNKNOWN, COMMITTED }

enum class PersistencePhase { PREPARING, VERIFIED, BACKED_UP, STAGED, PROMOTING, SAVED, CANCELED, RECOVERY_REQUIRED }
enum class PersistenceCommit { COMMITTED, CANCELED, UNKNOWN }
enum class JournalAction { NONE, DISCARD, PROMOTE, BLOCK }

class PersistenceWorkflow(private val requiresBackup: Boolean, private val metadataTransaction: Boolean) {
    var phase = PersistencePhase.PREPARING
        private set
    fun verified(permitted: Boolean): Boolean {
        if (phase != PersistencePhase.PREPARING) return false
        phase = if (permitted) PersistencePhase.VERIFIED else PersistencePhase.CANCELED
        return permitted
    }
    fun backedUp(): Boolean {
        if (phase != PersistencePhase.VERIFIED || !requiresBackup) return false
        phase = PersistencePhase.BACKED_UP
        return true
    }
    fun staged(): Boolean {
        if (!metadataTransaction || !ready()) return false
        phase = PersistencePhase.STAGED
        return true
    }
    fun beginPromotion(permitted: Boolean): Boolean {
        if (metadataTransaction || !ready()) return false
        phase = if (permitted) PersistencePhase.PROMOTING else PersistencePhase.CANCELED
        return permitted
    }
    /** Once metadata may have changed, cancellation cannot authorize discarding the journal. */
    fun metadataCommitted(decision: PersistenceCommit, markerMatches: Boolean): Boolean {
        if (phase != PersistencePhase.STAGED) return false
        phase = when {
            decision == PersistenceCommit.CANCELED && !markerMatches -> PersistencePhase.CANCELED
            decision == PersistenceCommit.COMMITTED && markerMatches -> PersistencePhase.PROMOTING
            else -> PersistencePhase.RECOVERY_REQUIRED
        }
        return phase == PersistencePhase.PROMOTING
    }
    fun promoted(): Boolean {
        if (phase != PersistencePhase.PROMOTING) return false
        phase = PersistencePhase.SAVED
        return true
    }
    fun promotionFailed() {
        if (phase == PersistencePhase.PROMOTING) phase = PersistencePhase.RECOVERY_REQUIRED
    }
    private fun ready(): Boolean = phase == if (requiresBackup) PersistencePhase.BACKED_UP else PersistencePhase.VERIFIED
    override fun toString() = "PersistenceWorkflow(redacted)"
}

object PersistenceRecoveryPolicy {
    /** IDs are opaque equality tokens; decoding/bounding the journal remains native. */
    fun journal(present: Boolean, valid: Boolean, markerMatches: Boolean,
                livePromotion: Boolean, expectedMatches: Boolean, blocked: Boolean): JournalAction = when {
        blocked -> JournalAction.BLOCK
        !present -> if (livePromotion) JournalAction.BLOCK else JournalAction.NONE
        !valid || (livePromotion && (!expectedMatches || !markerMatches)) -> JournalAction.BLOCK
        markerMatches -> JournalAction.PROMOTE
        else -> JournalAction.DISCARD
    }
    fun canInitialize(vaultExists: Boolean, backupExists: Boolean, pendingExists: Boolean): Boolean =
        !vaultExists && !backupExists && !pendingExists
}
