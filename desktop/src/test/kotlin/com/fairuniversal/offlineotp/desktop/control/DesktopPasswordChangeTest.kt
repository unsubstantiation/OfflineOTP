package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPasswordChangeTest {
    private class Queue : Executor {
        val tasks = arrayListOf<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private class Key : SecretKey {
        var destroyed = false
        override fun getAlgorithm() = "AES"
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(32)
        override fun destroy() { destroyed = true }
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
    private fun entry() = ManualEntryInput(label = "alice", secret = "JBSWY3DPEHPK3PXP").build()

    @Test fun realRekeyLocksOldSessionAndRequiresFreshUnlock() {
        val dir = Files.createTempDirectory("compose-rekey-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("old-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key); val worker = Queue(); val ui = Queue()
        assertTrue(EntryEditor(controller, state).add(entry()))
        val owner = DesktopPasswordChange(state, worker, ui::execute, state::lock, controller::changePassword, {})
        try {
            assertTrue(owner.submit("old-password", "new-password", "new-password") { true })
            assertTrue(state.isLocked); assertTrue(state.snapshot().isEmpty())
            assertFalse(owner.submit("old-password", "other", "other") { true })
            worker.run(); assertEquals(DesktopPasswordChange.Status.WORKING, owner.status)
            ui.run(); assertEquals(DesktopPasswordChange.Status.CHANGED, owner.status)
            assertTrue(controller.openVault("old-password") is VaultController.Session.Error)
            val reopened = controller.openVault("new-password") as VaultController.Session.Loaded
            try { assertEquals("alice", reopened.entries.single().getLabel()) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { owner.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun wrongCurrentPasswordLeavesDiskReadableOnlyWithOriginalPassword() {
        val dir = Files.createTempDirectory("compose-rekey-wrong-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("original") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key); val worker = Queue(); val ui = Queue()
        val owner = DesktopPasswordChange(state, worker, ui::execute, state::lock, controller::changePassword, {})
        try {
            owner.submit("incorrect", "replacement", "replacement") { true }; worker.run(); ui.run()
            assertEquals(DesktopPasswordChange.Status.FAILED, owner.status); assertTrue(state.isLocked)
            assertTrue(controller.openVault("replacement") is VaultController.Session.Error)
            val reopened = controller.openVault("original") as VaultController.Session.Loaded
            DesktopUnlockOperation.discard(reopened)
        } finally { owner.close(); state.lock(); dir.deleteRecursively() }
    }

    @Test fun invalidOrExpiredInputDoesNotLockOrScheduleWork() {
        val state = state(); val worker = Queue(); val ui = Queue(); var locks = 0
        val owner = DesktopPasswordChange(state, worker, ui::execute, { locks++ }, { _, _ -> error("unexpected") }, {})
        try {
            assertFalse(owner.submit("old", "new", "different") { true })
            assertFalse(owner.submit("old", "old", "old") { true })
            assertFalse(owner.submit("", "new", "new") { true })
            assertFalse(owner.submit("old", "x".repeat(1025), "x".repeat(1025)) { true })
            assertFalse(owner.submit("old", "new", "new") { false })
            assertEquals(0, locks); assertTrue(worker.tasks.isEmpty()); assertFalse(state.isLocked)
        } finally { owner.close(); state.lock() }
    }
    @Test fun hostMustActuallyLockAndReentrantClosePreventsWorker() {
        for (closeDuringLock in listOf(false, true)) {
            val state = state(); val worker = Queue(); val ui = Queue()
            lateinit var owner: DesktopPasswordChange
            owner = DesktopPasswordChange(state, worker, ui::execute, {
                if (closeDuringLock) { state.lock(); owner.close() }
            }, { _, _ -> error("unexpected") }, {})
            try {
                assertFalse(owner.submit("old", "new", "new") { true })
                assertTrue(worker.tasks.isEmpty())
                if (!closeDuringLock) assertEquals(DesktopPasswordChange.Status.FAILED, owner.status)
            } finally { owner.close(); state.lock() }
        }
    }
    @Test fun cancellationBeforeWorkSkipsRekeyAndDuringWorkDiscardsReturnedMaterialImmediately() {
        val state = state(); val worker = Queue(); val ui = Queue(); var calls = 0
        val owner = DesktopPasswordChange(state, worker, ui::execute, state::lock, { _, _ -> calls++; error("unexpected") }, {})
        owner.submit("old", "new", "new") { true }; owner.close(); worker.run(); assertEquals(0, calls)
        val nextState = state(); val key = Key(); val entry = entry()
        val result = VaultController.Session.Loaded(arrayListOf(entry), key)
        lateinit var next: DesktopPasswordChange
        next = DesktopPasswordChange(nextState, worker, ui::execute, nextState::lock, { _, _ -> next.close(); result }, {})
        try {
            next.submit("old", "new", "new") { true }; worker.run()
            assertTrue(key.destroyed); assertEquals(0, entry.getSecretSize()); assertTrue(result.entries.isEmpty())
            ui.run(); assertEquals(DesktopPasswordChange.Status.WORKING, next.status)
        } finally { next.close(); nextState.lock() }
    }
    @Test fun failuresLeaveSessionLockedAndRejectedDispatchCannotRetainNewKeys() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val failed = DesktopPasswordChange(state, worker, ui::execute, state::lock, { _, _ -> error("backend") }, {})
        failed.submit("old", "new", "new") { true }; worker.run(); ui.run()
        assertTrue(state.isLocked); assertEquals(DesktopPasswordChange.Status.FAILED, failed.status); failed.close()
        val second = state(); val key = Key(); val result = VaultController.Session.Loaded(arrayListOf(entry()), key)
        val rejected = DesktopPasswordChange(second, worker, { throw RejectedExecutionException() }, second::lock, { _, _ -> result }, {})
        rejected.submit("old", "new", "new") { true }; worker.run()
        assertTrue(key.destroyed); assertTrue(result.entries.isEmpty()); rejected.close()
        val third = state()
        val noWorker = DesktopPasswordChange(third, Executor { throw RejectedExecutionException() }, ui::execute, third::lock,
            { _, _ -> error("unexpected") }, {})
        noWorker.submit("old", "new", "new") { true }
        assertTrue(third.isLocked); assertEquals(DesktopPasswordChange.Status.FAILED, noWorker.status); noWorker.close()
    }
}
