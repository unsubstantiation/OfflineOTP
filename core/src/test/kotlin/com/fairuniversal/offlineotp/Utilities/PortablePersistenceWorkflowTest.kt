package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortablePersistenceWorkflowTest {
    @Test fun destructiveReplacementRequiresVerificationAndBackupBeforePromotion() {
        val flow = PersistenceWorkflow(true, false)
        assertFalse(flow.beginPromotion(true)); assertFalse(flow.backedUp()); assertFalse(flow.promoted())
        assertTrue(flow.verified(true)); assertFalse(flow.beginPromotion(true))
        assertTrue(flow.backedUp()); assertFalse(flow.backedUp())
        assertTrue(flow.beginPromotion(true)); assertFalse(flow.beginPromotion(true))
        assertTrue(flow.promoted()); assertFalse(flow.promoted())
        assertEquals(PersistencePhase.SAVED, flow.phase)
        assertFalse(flow.verified(true))
    }
    @Test fun atomicSaveNeedsNoBackupButStillRequiresVerifiedCandidate() {
        val flow = PersistenceWorkflow(false, false)
        assertFalse(flow.backedUp()); assertFalse(flow.staged())
        assertTrue(flow.verified(true)); assertTrue(flow.beginPromotion(true)); assertTrue(flow.promoted())
    }
    @Test fun cancellationBeforeOrAfterBackupIsTerminalAndNeverPromotes() {
        val initial = PersistenceWorkflow(false, false)
        assertFalse(initial.verified(false)); assertFalse(initial.verified(true)); assertFalse(initial.beginPromotion(true))
        val backed = PersistenceWorkflow(true, false)
        backed.verified(true); backed.backedUp()
        assertFalse(backed.beginPromotion(false)); assertFalse(backed.beginPromotion(true))
        assertEquals(PersistencePhase.CANCELED, backed.phase)
    }
    @Test fun metadataCommitDecisionMatrixQuarantinesEveryAmbiguousOutcome() {
        for (decision in PersistenceCommit.values()) for (matches in listOf(false, true)) {
            val flow = PersistenceWorkflow(true, true)
            assertFalse(flow.staged()); flow.verified(true); assertFalse(flow.staged())
            flow.backedUp(); assertTrue(flow.staged()); assertFalse(flow.beginPromotion(true))
            val permitted = flow.metadataCommitted(decision, matches)
            assertEquals(decision == PersistenceCommit.COMMITTED && matches, permitted)
            assertEquals(when {
                permitted -> PersistencePhase.PROMOTING
                decision == PersistenceCommit.CANCELED && !matches -> PersistencePhase.CANCELED
                else -> PersistencePhase.RECOVERY_REQUIRED
            }, flow.phase)
            assertFalse(flow.metadataCommitted(PersistenceCommit.COMMITTED, true))
        }
    }
    @Test fun promotionFailureCannotBeRetriedAsANewWrite() {
        val flow = PersistenceWorkflow(false, false)
        flow.verified(true); flow.beginPromotion(true); flow.promotionFailed()
        assertEquals(PersistencePhase.RECOVERY_REQUIRED, flow.phase)
        assertFalse(flow.promoted()); assertFalse(flow.beginPromotion(true)); assertFalse(flow.verified(true))
    }
    @Test fun journalRecoveryTruthTableNeverDiscardsCommittedOrMalformedWork() {
        for (present in listOf(false, true)) for (valid in listOf(false, true))
        for (marker in listOf(false, true)) for (live in listOf(false, true))
        for (expected in listOf(false, true)) for (blocked in listOf(false, true)) {
            val action = PersistenceRecoveryPolicy.journal(present, valid, marker, live, expected, blocked)
            val wanted = when {
                blocked -> JournalAction.BLOCK
                !present -> if (live) JournalAction.BLOCK else JournalAction.NONE
                !valid || (live && (!expected || !marker)) -> JournalAction.BLOCK
                marker -> JournalAction.PROMOTE
                else -> JournalAction.DISCARD
            }
            assertEquals(wanted, action)
        }
    }
    @Test fun initializationCannotOverwriteAnyRecoveryArtifact() {
        for (vault in listOf(false, true)) for (backup in listOf(false, true)) for (pending in listOf(false, true))
            assertEquals(!vault && !backup && !pending, PersistenceRecoveryPolicy.canInitialize(vault, backup, pending))
    }
}
