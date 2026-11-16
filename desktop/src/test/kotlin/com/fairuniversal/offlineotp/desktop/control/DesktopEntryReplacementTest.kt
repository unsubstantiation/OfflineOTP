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

class DesktopEntryReplacementTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { tasks.removeAt(0).run() }
    }
    private fun entry(label: String) = ManualEntryInput(label = label, secret = "JBSWY3DPEHPK3PXP").build()
    @Test fun savePrecedesReplacementAndPreservesOrderAndIdentity() {
        val dir = Files.createTempDirectory("async-replace-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key); val first = entry("first"); val target = entry("old"); val last = entry("last")
        listOf(first, target, last).forEach { state.add(it) }; val id = target.getListId()
        val draft = ManualEntryInput.fromEntry(target).copy(label = "edited").build(target)
        val worker = Queue(); val ui = Queue()
        val owner = DesktopEntryTransaction(state, controller.detachedSave(worker, { ui.execute(it) }), state::lock)
        try {
            var result: DesktopEntryTransaction.Result? = null
            assertTrue(owner.submitReplacement(target, draft, { true }) { result = it })
            draft.destroy(); worker.run()
            assertTrue(state.snapshot()[1] === target); assertTrue(target.getSecretSize() > 0)
            ui.run(); assertEquals(DesktopEntryTransaction.Result.REPLACED, result)
            assertEquals(listOf("first", "edited", "last"), state.snapshot().map { it.getLabel() })
            assertEquals(id, state.snapshot()[1].getListId()); assertEquals(0, target.getSecretSize())
            assertTrue(state.snapshot()[0] === first); assertTrue(state.snapshot()[2] === last)
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try { assertEquals(listOf("first", "edited", "last"), reopened.entries.map { it.getLabel() }) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { owner.close(); draft.destroy(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun failureRetainsOriginalAndCallerDraftWhileDuplicatesAndStaleTargetsNeverQueue() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val target = entry("old"); val duplicate = entry("other")
        state.add(target); state.add(duplicate); val draft = entry("edited"); val worker = Queue(); val ui = Queue()
        val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { ui.execute(it) }) { _, _ -> false }, state::lock)
        try {
            assertFalse(owner.submitReplacement(target, duplicate, { true }) {})
            assertTrue(worker.tasks.isEmpty())
            var result: DesktopEntryTransaction.Result? = null
            assertTrue(owner.submitReplacement(target, draft, { true }) { result = it })
            worker.run(); ui.run()
            assertEquals(DesktopEntryTransaction.Result.SAVE_FAILED, result)
            assertTrue(state.snapshot()[0] === target); assertTrue(target.getSecretSize() > 0); assertTrue(draft.getSecretSize() > 0)
            state.replace(target, entry("external"))
            assertFalse(owner.submitReplacement(target, draft, { true }) {})
            assertTrue(worker.tasks.isEmpty())
        } finally { owner.close(); draft.destroy(); state.lock() }
    }
    @Test fun interveningInPlaceEditLocksInsteadOfAdoptingReplacement() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val target = entry("old"); state.add(target)
        val draft = entry("edited"); val worker = Queue(); val ui = Queue()
        val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { ui.execute(it) }) { _, _ -> true }, state::lock)
        try {
            assertTrue(owner.submitReplacement(target, draft, { true }) { error("stale") })
            worker.run(); target.setLabel("external"); ui.run(); assertTrue(state.isLocked)
            assertTrue(draft.getSecretSize() > 0)
        } finally { owner.close(); draft.destroy(); state.lock() }
    }
}
