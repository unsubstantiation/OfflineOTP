package com.fairuniversal.offlineotp.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultDialogsTest {
    @Test
    fun closedSessionCannotReopenDialogsOrPublishLateResults() {
        var changes = 0
        val dialogs = VaultDialogs { changes++ }
        dialogs.open(VaultDialogKind.PIN, 1L)
        val request = dialogs.pending!!
        dialogs.close()
        for (kind in VaultDialogKind.values()) dialogs.open(kind, 1L)
        dialogs.complete(request, false)
        dialogs.dismiss(request)
        dialogs.clear()
        dialogs.close()
        assertFalse(dialogs.isCurrent(request))
        assertNull(dialogs.pending)
        assertEquals(2, changes)
    }

    @Test
    fun openingAnotherDialogReplacesTheOldRequestAndItsError() {
        val dialogs = VaultDialogs()
        dialogs.open(VaultDialogKind.PIN, 1L)
        dialogs.complete(dialogs.pending!!, false)
        assertTrue(dialogs.pending!!.failed)
        dialogs.open(VaultDialogKind.DELETE, 2L)
        assertEquals(VaultDialogKind.DELETE, dialogs.pending!!.kind)
        assertEquals(2L, dialogs.pending!!.entryId)
        assertFalse(dialogs.pending!!.failed)
    }

    @Test
    fun staleCallbacksCannotDismissOrCompleteANewRequestForTheSameEntry() {
        val dialogs = VaultDialogs()
        dialogs.open(VaultDialogKind.COUNTER, 1L)
        val old = dialogs.pending!!
        dialogs.dismiss(old)
        dialogs.open(VaultDialogKind.COUNTER, 1L)
        val current = dialogs.pending!!
        assertFalse(dialogs.isCurrent(old))
        dialogs.complete(old, true)
        dialogs.dismiss(old)
        assertTrue(dialogs.isCurrent(current))
    }

    @Test
    fun retryUsesANewRequestAndSuccessClosesIt() {
        var changes = 0
        val dialogs = VaultDialogs { changes++ }
        dialogs.open(VaultDialogKind.PIN, 1L)
        val first = dialogs.pending!!
        dialogs.complete(first, false)
        assertFalse(dialogs.isCurrent(first))
        val retry = dialogs.pending!!
        assertEquals(first.kind, retry.kind)
        assertEquals(first.entryId, retry.entryId)
        assertTrue(retry.failed)
        dialogs.complete(retry, true)
        assertNull(dialogs.pending)
        assertEquals(3, changes)
    }

    @Test
    fun clearingOnLockInvalidatesEveryOutstandingCallback() {
        val dialogs = VaultDialogs()
        for (kind in VaultDialogKind.values()) {
            dialogs.open(kind, 1L)
            val old = dialogs.pending!!
            dialogs.clear()
            assertFalse(dialogs.isCurrent(old))
            dialogs.complete(old, false)
            assertNull(dialogs.pending)
        }
    }

    @Test
    fun completedOrCancelledActionsCannotBeSubmittedTwice() {
        val dialogs = VaultDialogs()
        dialogs.open(VaultDialogKind.DELETE, 1L)
        val request = dialogs.pending!!
        dialogs.complete(request, true)
        assertFalse(dialogs.isCurrent(request))
        dialogs.complete(request, false)
        assertNull(dialogs.pending)
        dialogs.open(VaultDialogKind.DELETE, 1L)
        val cancelled = dialogs.pending!!
        dialogs.dismiss(cancelled)
        assertFalse(dialogs.isCurrent(cancelled))
    }
}
