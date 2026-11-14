package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEntryDeletionTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { tasks.removeAt(0).run() }
    }
    private fun entry(label: String) = ManualEntryInput(label = label, secret = "JBSWY3DPEHPK3PXP").build()

    @Test fun actualSavePrecedesLiveDeletionAndReopensWithoutTarget() {
        val dir = Files.createTempDirectory("async-delete-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key); val target = entry("remove"); val kept = entry("keep")
        state.add(target); state.add(kept); assertTrue(controller.save(state.key, state.snapshot()))
        val worker = Queue(); val ui = Queue()
        val owner = DesktopEntryDeletion(state, controller.detachedSave(worker, { ui.execute(it) }), state::lock)
        var result: DesktopEntryDeletion.Result? = null
        try {
            assertTrue(owner.submit(target, { true }) { result = it })
            assertEquals(2, state.snapshot().size); assertTrue(owner.busy)
            assertFalse(owner.submit(kept, { true }) {})
            worker.run(); assertEquals(2, state.snapshot().size)
            ui.run(); assertEquals(DesktopEntryDeletion.Result.DELETED, result)
            assertTrue(state.snapshot().single() === kept); assertFalse(owner.busy)
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try { assertEquals("keep", reopened.entries.single().getLabel()) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { owner.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun failedWriteKeepsOriginalAndCanRetry() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val target = entry("keep")
        state.add(target); val worker = Queue(); val ui = Queue(); var succeeds = false
        val owner = DesktopEntryDeletion(state, DesktopDetachedSave(worker, { ui.execute(it) }) { _, entries ->
            assertTrue(entries.isEmpty()); succeeds
        }, state::lock)
        try {
            var result: DesktopEntryDeletion.Result? = null
            owner.submit(target, { true }) { result = it }; worker.run(); ui.run()
            assertEquals(DesktopEntryDeletion.Result.SAVE_FAILED, result)
            assertTrue(state.snapshot().single() === target); assertFalse(state.isLocked)
            succeeds = true; owner.submit(target, { true }) { result = it }; worker.run(); ui.run()
            assertEquals(DesktopEntryDeletion.Result.DELETED, result); assertTrue(state.snapshot().isEmpty())
        } finally { owner.close(); state.lock() }
    }
    @Test fun expiredOrChangedSessionLocksInsteadOfAdoptingCandidate() {
        for (change in listOf(false, true)) {
            val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val target = entry("target")
            state.add(target); val worker = Queue(); val ui = Queue(); var active = true
            val owner = DesktopEntryDeletion(state, DesktopDetachedSave(worker, { ui.execute(it) }) { _, _ -> true }, state::lock)
            try {
                owner.submit(target, { active }) { error("stale adoption") }; worker.run()
                if (change) state.add(entry("new")) else active = false
                ui.run(); assertTrue(state.isLocked)
            } finally { owner.close(); state.lock() }
        }
    }
    @Test fun closingQueuedTransactionLocksAndCancelsWrite() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val target = entry("target")
        state.add(target); val worker = Queue()
        val owner = DesktopEntryDeletion(state, DesktopDetachedSave(worker, { it.run() }) { _, _ -> error("canceled write") }, state::lock)
        owner.submit(target, { true }) { error("late") }; owner.close(); worker.run()
        assertTrue(state.isLocked); assertFalse(owner.busy)
    }
}
