package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.util.concurrent.Executor
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopDetachedSaveTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { tasks.removeAt(0).run() }
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 7 }, "AES")).also {
        it.add(ManualEntryInput(label = "before", secret = "JBSWY3DPEHPK3PXP").build())
    }
    @Test fun workerSaveRoundTripsThroughActualVaultStorage() {
        val dir = java.nio.file.Files.createTempDirectory("detached-save-").toFile()
        val controller = VaultController(com.fairuniversal.offlineotp.desktop.storage.DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        state.add(ManualEntryInput(label = "saved snapshot", secret = "JBSWY3DPEHPK3PXP").build())
        val worker = Queue(); val ui = Queue(); var result: Boolean? = null
        val owner = DesktopDetachedSave(worker, { ui.execute(it) }, persist = controller::save)
        try {
            assertTrue(owner.submit(state, { true }) { result = it })
            state.snapshot().single().setLabel("not submitted")
            worker.run(); ui.run(); assertEquals(true, result)
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try { assertEquals("saved snapshot", reopened.entries.single().getLabel()) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { owner.close(); state.lock(); dir.deleteRecursively() }
    }

    @Test fun snapshotsDoNotShareLiveEntriesAndInputsAreDestroyedBeforeDelivery() {
        val state = state(); val worker = Queue(); val ui = Queue()
        var heldKey: SecretKey? = null; var heldEntries: ArrayList<Entry>? = null; var result: Boolean? = null
        val owner = DesktopDetachedSave(worker, { ui.execute(it) }) { key, entries ->
            heldKey = key; heldEntries = entries
            assertEquals("before", entries.single().getLabel())
            assertFalse(entries.single() === state.snapshot().single()); true
        }
        try {
            assertTrue(owner.submit(state, { true }) { result = it })
            assertFalse(owner.submit(state, { true }) { error("second") })
            state.snapshot().single().setLabel("after")
            worker.run()
            assertTrue(heldKey!!.isDestroyed); assertTrue(heldEntries!!.isEmpty())
            assertTrue(owner.busy); assertTrue(result == null)
            ui.run(); assertEquals(true, result); assertFalse(owner.busy)
            assertEquals("after", state.snapshot().single().getLabel())
        } finally { owner.close(); state.lock() }
    }
    @Test fun closeBeforeWorkerPreventsWriteAndCloseDuringWriteSuppressesDelivery() {
        for (before in listOf(true, false)) {
            val state = state(); val worker = Queue(); val ui = Queue(); var calls = 0
            lateinit var owner: DesktopDetachedSave
            owner = DesktopDetachedSave(worker, { ui.execute(it) }) { _, _ -> calls++; owner.close(); state.lock(); true }
            try {
                owner.submit(state, { true }) { error("late result") }
                if (before) owner.close()
                worker.run(); while (ui.tasks.isNotEmpty()) ui.run()
                assertEquals(if (before) 0 else 1, calls)
            } finally { owner.close(); state.lock() }
        }
    }
    @Test fun failedSaveDestroysInputsAndExpiredResultDoesNotPublish() {
        val state = state(); val worker = Queue(); val ui = Queue(); var live = true
        var key: SecretKey? = null; var entries: ArrayList<Entry>? = null
        val owner = DesktopDetachedSave(worker, { ui.execute(it) }) { k, e -> key = k; entries = e; error("storage") }
        try {
            owner.submit(state, { live }) { error("expired") }; worker.run(); live = false; ui.run()
            assertTrue(key!!.isDestroyed); assertTrue(entries!!.isEmpty()); assertFalse(owner.busy)
            live = true; var result: Boolean? = null
            owner.submit(state, { live }) { result = it }; worker.run(); ui.run()
            assertEquals(false, result)
        } finally { owner.close(); state.lock() }
    }
    @Test fun rejectedWorkerAllowsRetryButRejectedDeliveryRemainsBusy() {
        val state = state()
        val rejected = DesktopDetachedSave(Executor { error("rejected") }, { it.run() }) { _, _ -> error("not run") }
        try { assertFalse(rejected.submit(state, { true }) {}); assertFalse(rejected.busy) }
        finally { rejected.close() }
        val worker = Queue(); var key: SecretKey? = null
        val owner = DesktopDetachedSave(worker, { error("UI rejected") }) { k, _ -> key = k; true }
        try {
            assertTrue(owner.submit(state, { true }) {}); worker.run()
            assertTrue(key!!.isDestroyed); assertTrue(owner.busy)
            assertFalse(owner.submit(state, { true }) {})
        } finally { owner.close(); state.lock() }
    }
    @Test fun postSnapshotGuardFailureNeverQueuesAndCanCloseReentrantly() {
        val state = state(); val worker = Queue()
        val owner = DesktopDetachedSave(worker, { it.run() }) { _, _ -> error("not run") }
        try {
            var checks = 0
            assertFalse(owner.submit(state, { if (++checks == 2) error("guard failed") else true }) {})
            assertEquals(2, checks); assertTrue(worker.tasks.isEmpty())
            assertFalse(owner.submit(state, { owner.close(); true }) {})
            assertTrue(worker.tasks.isEmpty())
        } finally { owner.close(); state.lock() }
    }
}
