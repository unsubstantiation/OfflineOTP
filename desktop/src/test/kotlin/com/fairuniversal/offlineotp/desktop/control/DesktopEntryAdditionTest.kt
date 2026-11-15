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

class DesktopEntryAdditionTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { tasks.removeAt(0).run() }
    }
    private fun draft() = ManualEntryInput(label = "new", secret = "JBSWY3DPEHPK3PXP").build()
    @Test fun newEntryIsDetachedPersistedAndAdoptedOnlyAfterSuccess() {
        val dir = Files.createTempDirectory("background-add-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key); val worker = Queue(); val ui = Queue(); val draft = draft()
        val owner = DesktopEntryTransaction(state, controller.detachedSave(worker, { ui.execute(it) }), state::lock)
        try {
            var result: DesktopEntryTransaction.Result? = null
            assertTrue(owner.submitNew(draft, { true }) { result = it })
            draft.destroy() // Transaction must not borrow the caller's entry.
            assertTrue(state.snapshot().isEmpty()); worker.run(); assertTrue(state.snapshot().isEmpty())
            ui.run(); assertEquals(DesktopEntryTransaction.Result.ADDED, result)
            assertEquals("new", state.snapshot().single().getLabel())
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try { assertEquals(state.snapshot().single().toJSON().toString(), reopened.entries.single().toJSON().toString()) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { owner.close(); draft.destroy(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun failureRetainsCallerDraftAndDoesNotAddToLiveState() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val worker = Queue(); val ui = Queue()
        val draft = draft()
        val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { ui.execute(it) }) { _, entries ->
            assertEquals("new", entries.single().getLabel()); false
        }, state::lock)
        try {
            var result: DesktopEntryTransaction.Result? = null
            owner.submitNew(draft, { true }) { result = it }; worker.run(); ui.run()
            assertEquals(DesktopEntryTransaction.Result.SAVE_FAILED, result); assertTrue(state.snapshot().isEmpty())
            assertEquals("new", draft.getLabel()); assertFalse(owner.busy)
            assertTrue(owner.submitNew(draft, { true }) {}); owner.close(); worker.run()
            assertTrue(state.isLocked)
        } finally { owner.close(); draft.destroy(); state.lock() }
    }
    @Test fun duplicateAndDirectUnownedAddAreRejectedBeforeScheduling() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val draft = draft(); state.add(draft)
        val worker = Queue()
        val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { it.run() }) { _, _ -> error("invalid") }, state::lock)
        try {
            assertFalse(owner.submitNew(draft, { true }) {})
            assertFalse(owner.submit(draft, DesktopEntryTransaction.Kind.ADD, { true }) {})
            assertTrue(worker.tasks.isEmpty()); assertEquals("new", state.snapshot().single().getLabel())
        } finally { owner.close(); state.lock() }
    }
}
