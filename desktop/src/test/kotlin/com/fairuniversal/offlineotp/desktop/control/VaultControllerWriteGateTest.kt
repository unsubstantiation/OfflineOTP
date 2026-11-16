package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultControllerWriteGateTest {
    @Test fun detachedSaveBlocksAllVaultAccessUntilUiAcknowledges() {
        val dir = Files.createTempDirectory("controller-write-gate-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        state.add(ManualEntryInput(label = "snapshot", secret = "JBSWY3DPEHPK3PXP").build())
        val worker = ArrayList<Runnable>(); val ui = ArrayList<Runnable>()
        val owner = controller.detachedSave(Executor { worker.add(it) }, { ui.add(it) })
        val other = controller.detachedSave(Executor { worker.add(it) }, { ui.add(it) })
        fun blocked() {
            assertTrue(controller.persistenceBusy)
            assertFalse(controller.save(state.key, arrayListOf()))
            for (result in listOf(controller.openVault("password"), controller.unlockWithKey(state.key),
                controller.createVault("other"), controller.changePassword("password", "new-password"))) {
                assertEquals(VaultController.Failure.IO_FAILURE, (result as VaultController.Session.Error).failure)
            }
            assertFalse(other.submit(state, { true }) {})
        }
        try {
            assertTrue(owner.submit(state, { true }) { saved -> assertTrue(saved); blocked() })
            blocked(); worker.removeAt(0).run(); blocked(); ui.removeAt(0).run()
            assertFalse(controller.persistenceBusy)
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try { assertEquals("snapshot", reopened.entries.single().getLabel()) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { owner.close(); other.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun cancelQueuedControllerSaveRestoresAdmissionWithoutWriting() {
        val dir = Files.createTempDirectory("controller-canceled-write-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key); val worker = ArrayList<Runnable>()
        state.add(ManualEntryInput(label = "not saved", secret = "JBSWY3DPEHPK3PXP").build())
        val owner = controller.detachedSave(Executor { worker.add(it) }, { it.run() })
        try {
            assertTrue(owner.submit(state, { true }) { error("canceled") })
            owner.close(); assertFalse(controller.persistenceBusy); worker.removeAt(0).run()
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try { assertTrue(reopened.entries.isEmpty()) } finally { DesktopUnlockOperation.discard(reopened) }
            assertFalse(controller.persistenceBusy)
        } finally { owner.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun exclusiveGateRejectsNestedOperationAndReleasesOnException() {
        val gate = DesktopWriteGate()
        try { gate.exclusive(false) {
            assertTrue(gate.busy)
            assertFalse(gate.exclusive(false) { error("must not run") })
            error("storage")
        } } catch (_: IllegalStateException) {}
        assertFalse(gate.busy)
        assertTrue(gate.exclusive(false) { true })
    }
}
