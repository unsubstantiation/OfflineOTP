package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSaveRevisionTest {
    @Test fun onlySuccessfulSavesSignalBackupChangesAndDetachedSavesSignalAfterWrite() {
        val folder = Files.createTempDirectory("save-revision-").toFile()
        val controller = VaultController(DesktopStorage.open(folder))
        val loaded = controller.createVault("synthetic password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val entry = ManualEntryInput(label = "synthetic", secret = "JBSWY3DPEHPK3PXP").build()
        val queued = arrayListOf<Runnable>(); val delivered = arrayListOf<Runnable>()
        val saver = controller.detachedSave({ queued.add(it) }, { delivered.add(it) })
        try {
            val before = controller.persistedRevision
            state.add(entry)
            controller.saveSortMode(com.fairuniversal.offlineotp.Utilities.Constants.SortMode.UNSORTED)
            assertEquals(before, controller.persistedRevision)
            assertTrue(controller.save(state.key, state.snapshot()))
            assertEquals(before + 1, controller.persistedRevision)
            val invalid = com.fairuniversal.offlineotp.Database.Entry()
            assertFalse(controller.save(state.key, arrayListOf(invalid)))
            assertEquals(before + 1, controller.persistedRevision)
            assertTrue(saver.submit(state, { true }) {})
            assertEquals(before + 1, controller.persistedRevision)
            queued.removeAt(0).run()
            assertEquals(before + 2, controller.persistedRevision)
            delivered.removeAt(0).run()
        } finally { saver.close(); state.lock(); entry.destroy(); folder.deleteRecursively() }
    }
}
