package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreWorkflowTest {
    @Test fun decodeIsReadOnlyAndConfirmationAndInstallAreSingleUse() {
        val flow = RestoreWorkflow(); val ticket = flow.start(7, false)
        assertFalse(flow.beginInstall(ticket, 7, true, 2))
        assertFalse(flow.confirm(ticket, 7, true, true))
        assertTrue(flow.decoded(ticket, 7, true, 2, true))
        assertEquals(RestorePhase.PREVIEW, flow.phase)
        assertFalse(flow.beginInstall(ticket, 7, true, 2))
        assertTrue(flow.confirm(ticket, 7, true, false)); assertFalse(flow.replace)
        assertFalse(flow.confirm(ticket, 7, true, true)); assertFalse(flow.replace)
        assertTrue(flow.beginInstall(ticket, 7, true, 3))
        assertFalse(flow.beginInstall(ticket, 7, true, 3))
        assertTrue(flow.installed(ticket, true)); assertEquals(RestorePhase.SUCCEEDED, flow.phase)
        assertFalse(flow.installed(ticket, false)); assertFalse(flow.confirm(ticket, 7, true, true))
    }
    @Test fun cancellationAtEveryBoundaryRevokesFurtherAuthority() {
        for (boundary in 0..3) {
            val flow = RestoreWorkflow(); val ticket = flow.start(7, false)
            if (boundary >= 1) flow.decoded(ticket, 7, true, 1, true)
            if (boundary >= 2) flow.confirm(ticket, 7, true, true)
            if (boundary >= 3) flow.beginInstall(ticket, 7, true, 1)
            flow.cancel()
            assertFalse(flow.decoded(ticket, 7, true, 1, true)); assertFalse(flow.confirm(ticket, 7, true, true))
            assertFalse(flow.beginInstall(ticket, 7, true, 1)); assertFalse(flow.installed(ticket, true))
            assertEquals(RestorePhase.CANCELLED, flow.phase)
        }
    }
    @Test fun aStaleResultCannotCancelOrReplaceTheNewerRequest() {
        val flow = RestoreWorkflow(); val old = flow.start(1, false); val fresh = flow.start(2, false)
        assertFalse(flow.decoded(old, 1, true, 1, true)); assertFalse(flow.confirm(old, 1, false, true))
        assertEquals(RestorePhase.READING, flow.phase)
        assertTrue(flow.decoded(fresh, 2, true, 1, true)); assertTrue(flow.confirm(fresh, 2, true, true))
        assertFalse(flow.beginInstall(old, 1, true, 1)); assertTrue(flow.beginInstall(fresh, 2, true, 1))
    }
    @Test fun generationOrDestinationRevocationRejectsTheCurrentRequest() {
        for (permitted in listOf(false, true)) {
            val flow = RestoreWorkflow(); val ticket = flow.start(7, false)
            flow.decoded(ticket, 7, true, 1, true)
            assertFalse(flow.confirm(ticket, if (permitted) 8 else 7, permitted, false))
            assertEquals(RestoreFailure.STALE, flow.failure)
        }
    }
    @Test fun malformedEmptyDuplicateAndOversizedDocumentsCannotBecomeAPreview() {
        for ((count, unique, failure) in listOf(Triple(-1, true, RestoreFailure.INVALID), Triple(0, true, RestoreFailure.EMPTY),
            Triple(1, false, RestoreFailure.DUPLICATE), Triple(2001, true, RestoreFailure.LIMIT))) {
            val flow = RestoreWorkflow(); val ticket = flow.start(1, false)
            assertFalse(flow.decoded(ticket, 1, true, count, unique)); assertEquals(failure, flow.failure)
            assertFalse(flow.confirm(ticket, 1, true, true)); assertFalse(flow.beginInstall(ticket, 1, true, 1))
        }
    }
    @Test fun firstRunAllowsAnEmptyVaultButStillRequiresConsentAndInstallAuthorization() {
        val flow = RestoreWorkflow(); val ticket = flow.start(0, true)
        assertTrue(flow.decoded(ticket, 0, true, 0, true)); assertFalse(flow.beginInstall(ticket, 0, true, 0))
        assertTrue(flow.confirm(ticket, 0, true, false)); assertTrue(flow.replace)
        assertTrue(flow.beginInstall(ticket, 0, true, 0)); assertTrue(flow.installed(ticket, true))
    }
    @Test fun currentVaultGrowthAndStorageFailureNeverGrantAnotherWrite() {
        val flow = RestoreWorkflow(); var ticket = flow.start(0, false)
        flow.decoded(ticket, 0, true, 1, true); flow.confirm(ticket, 0, true, false)
        assertFalse(flow.beginInstall(ticket, 0, true, 2001)); assertEquals(RestoreFailure.LIMIT, flow.failure)
        ticket = flow.start(0, false); flow.decoded(ticket, 0, true, 1, true); flow.confirm(ticket, 0, true, false)
        assertTrue(flow.beginInstall(ticket, 0, true, 1)); assertFalse(flow.installed(ticket, false))
        assertEquals(RestoreFailure.STORAGE, flow.failure); assertFalse(flow.beginInstall(ticket, 0, true, 1))
    }
    @Test fun portableMergeKeepsCurrentMetadataAndOrderWithoutNativeObjects() {
        val policy = RestoreMergePolicy()
        val merge = policy.plan(listOf(false, true, false), 2, true, true, false)
        assertEquals(listOf(0, 2), merge.importedIndices); assertTrue(merge.retainCurrent); assertEquals(4, merge.resultCount)
        val replace = policy.plan(listOf(false, true, false), 2, true, true, true)
        assertEquals(listOf(0, 1, 2), replace.importedIndices); assertFalse(replace.retainCurrent); assertEquals(3, replace.resultCount)
        assertEquals(RestoreFailure.DUPLICATE, policy.plan(listOf(true, true), 1, false, true, false).failure)
        assertEquals(RestoreFailure.LIMIT, policy.plan(listOf(false), 2000, true, true, false).failure)
        assertEquals(2000, policy.plan(listOf(true), 2000, true, true, false).resultCount)
    }
}
