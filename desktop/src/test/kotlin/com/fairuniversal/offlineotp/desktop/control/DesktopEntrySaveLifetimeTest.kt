package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.EmailEntryInput
import com.fairuniversal.offlineotp.ui.vault.EmailPasswordInput
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEntrySaveLifetimeTest {
    private class Clock : ScreenTimerController.Scheduler {
        var now = 0L
        override fun postDelayed(runnable: Runnable, delayMillis: Long) {}
        override fun remove(runnable: Runnable) {}
        override fun elapsedRealtime() = now
    }
    private val manual = ManualEntryInput(secret = "JBSWY3DPEHPK3PXP", label = "original")
    private val email = EmailEntryInput(address = "alice@example.com", incomingHost = "imap.example.com", outgoingHost = "smtp.example.com")
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
    private fun original(kind: Int) = when (kind) {
        0 -> manual.build()
        1 -> Entry.passwordFields("alice", "site", listOf("url" to "site", "username" to "alice", "password" to "original"))
        else -> email.build()
    }
    private fun submit(edits: DesktopEntryEditing, request: DesktopEntryEditing.Request, kind: Int,
                       active: () -> Boolean) = when (kind) {
        0 -> edits.submit(request, manual.copy(label = "changed"), true, active)
        1 -> edits.submitPassword(request, PasswordEntryInput("site", "alice", "changed"), true, active)
        else -> edits.submitEmailPasswords(request, EmailPasswordInput("changed", listOf("")), true, active)
    }

    @Test fun editsThatCrossDeadlinePersistButLockBeforeReportingSuccess() {
        for (kind in 0..2) {
            val dir = Files.createTempDirectory("edit-deadline-").toFile()
            val controller = VaultController(DesktopStorage.open(dir))
            val loaded = controller.createVault("password") as VaultController.Session.Loaded
            val state = DesktopVaultState(loaded.key); val clock = Clock()
            val editor = EntryEditor(controller, state)
            val entry = original(kind); assertTrue(editor.add(entry))
            val edits = DesktopEntryEditing(state) { old, next ->
                editor.replace(old, next).also { clock.now = 1001 }
            }
            val lifetime = DesktopSessionLifetime(clock, 1) { edits.close(); state.lock() }
            try {
                edits.open(entry.getListId()); val request = edits.pending!!
                assertFalse(submit(edits, request, kind, lifetime::checkActive))
                assertTrue(state.isLocked); assertTrue(edits.pending == null)
                val reopened = controller.openVault("password") as VaultController.Session.Loaded
                try {
                    val saved = reopened.entries.single()
                    assertEquals("changed", when (kind) {
                        0 -> saved.getLabel()
                        1 -> saved.getPasswordFields().value("password")
                        else -> saved.getEmailAccount().account.getString("password")
                    })
                } finally { DesktopUnlockOperation.discard(reopened) }
            } finally { lifetime.close(); state.lock(); dir.deleteRecursively() }
        }
    }

    @Test fun bothCreationPathsLockAfterSaveCrossesDeadline() {
        for (isEmail in listOf(false, true)) {
            val state = state(); val clock = Clock(); var saved: Entry? = null
            val creation = DesktopEntryCreation(state) { draft ->
                saved = draft; state.add(draft); clock.now = 1001; true
            }
            val lifetime = DesktopSessionLifetime(clock, 1) { creation.close(); state.lock() }
            try {
                creation.open(); val request = creation.pending!!
                val success = if (isEmail) creation.submitEmail(request, email, lifetime::checkActive)
                    else creation.submit(request, manual, lifetime::checkActive)
                assertFalse(success); assertTrue(state.isLocked); assertTrue(creation.pending == null)
                assertEquals(0, saved!!.getSecretSize())
            } finally { lifetime.close(); state.lock() }
        }
    }

    @Test fun deadlineBeforeBuildingOrBeforeSaveNeverCallsPersistence() {
        for (expireAt in 1..2) {
            val state = state(); val entry = manual.build(); state.add(entry)
            var calls = 0; var checks = 0
            val edits = DesktopEntryEditing(state) { _, _ -> calls++; true }
            try {
                edits.open(entry.getListId())
                assertFalse(submit(edits, edits.pending!!, 0) { ++checks < expireAt })
                val creation = DesktopEntryCreation(state) { calls++; true }
                creation.open(); checks = 0
                assertFalse(creation.submit(creation.pending!!, manual) { ++checks < expireAt })
                assertEquals(0, calls)
            } finally { edits.close(); state.lock() }
        }
    }

    @Test fun oldSaveCompletionNeverClearsNewDialogOrDestroysAdoptedEntry() {
        val state = state(); val entry = manual.build(); state.add(entry)
        lateinit var edits: DesktopEntryEditing
        var nextRequest: DesktopEntryEditing.Request? = null
        edits = DesktopEntryEditing(state) { old, next ->
            state.replace(old, next)
            edits.open(next.getListId()); nextRequest = edits.pending
            true
        }
        try {
            edits.open(entry.getListId()); val old = edits.pending!!
            assertFalse(submit(edits, old, 0) { true })
            assertTrue(edits.pending === nextRequest)
            assertTrue(state.snapshot().single().getSecretSize() > 0)
            lateinit var creation: DesktopEntryCreation
            var nextCreation: DesktopEntryCreation.Request? = null
            creation = DesktopEntryCreation(state) { draft ->
                state.add(draft); creation.open(); nextCreation = creation.pending; true
            }
            creation.open(); val prior = creation.pending!!
            assertFalse(creation.submitEmail(prior, email))
            assertTrue(creation.pending === nextCreation)
        } finally { edits.close(); state.lock() }
    }

    @Test fun failureAndExceptionAlsoCheckDeadlineAndDestroyUnadoptedCandidate() {
        for (throwing in listOf(false, true)) {
            val state = state(); val clock = Clock(); val entry = manual.build(); state.add(entry)
            var candidate: Entry? = null
            val edits = DesktopEntryEditing(state) { _, next ->
                candidate = next; clock.now = 1001
                if (throwing) error("disk failure") else false
            }
            val lifetime = DesktopSessionLifetime(clock, 1) { edits.close(); state.lock() }
            try {
                edits.open(entry.getListId()); val request = edits.pending!!
                assertFalse(submit(edits, request, 0, lifetime::checkActive))
                assertTrue(state.isLocked); assertEquals(0, candidate!!.getSecretSize())
                assertTrue(request.failure == null) // No stale dialog mutation after locking.
            } finally { lifetime.close(); state.lock() }
        }
    }

    @Test fun reentrantGuardCannotAuthorizeRequestItJustDismissed() {
        val state = state(); val entry = manual.build(); state.add(entry)
        var saves = 0
        val edits = DesktopEntryEditing(state) { _, _ -> saves++; true }
        val creation = DesktopEntryCreation(state) { saves++; true }
        try {
            edits.open(entry.getListId()); val request = edits.pending!!
            assertFalse(submit(edits, request, 0) { edits.dismiss(request); true })
            creation.open(); val add = creation.pending!!
            assertFalse(creation.submit(add, manual) { creation.dismiss(add); true })
            assertEquals(0, saves)
        } finally { edits.close(); creation.close(); state.lock() }
    }
}
