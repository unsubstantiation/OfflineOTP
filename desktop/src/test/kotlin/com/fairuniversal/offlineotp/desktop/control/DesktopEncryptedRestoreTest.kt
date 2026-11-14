package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupCodec
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEncryptedRestoreTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private fun entry(label: String) = ManualEntryInput(label = label, secret = "JBSWY3DPEHPK3PXP").build()
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))

    @Test fun realBackupRequiresConfirmationAndMergePersistsCurrentAccounts() {
        val dir = Files.createTempDirectory("compose-restore-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("vault-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val worker = Queue(); val ui = Queue()
        val restore = DesktopEncryptedRestore(state, worker, ui::execute, {}, BackupCoordinator(controller, state)::restoreEntries)
        val backupEntries = arrayListOf(entry("imported"), entry("existing"))
        try {
            val existing = entry("existing")
            assertTrue(EntryEditor(controller, state).add(existing))
            val source = File(dir, "backup.aes")
            source.writeBytes(EncryptedBackupCodec.seal("backup-password", VaultJson.entriesToString(backupEntries)))
            restore.open(source); val request = restore.pending!!
            assertTrue(restore.submit(request, "backup-password") { true })
            assertFalse(restore.submit(request, "backup-password") { true })
            worker.run(); ui.run()
            assertEquals(2, request.count)
            assertEquals(1, state.snapshot().size)
            // An edit while decoding must be preserved by merge, not overwritten by a snapshot.
            assertTrue(EntryEditor(controller, state).add(entry("added meanwhile")))
            assertTrue(restore.confirm(request, false) { true })
            assertEquals(1, restore.restoredCount)
            assertTrue(state.snapshot().any { it === existing })
            assertEquals(listOf("imported", "existing", "added meanwhile"), state.snapshot().map { it.getLabel() })
            val reopened = controller.openVault("vault-password") as VaultController.Session.Loaded
            try { assertEquals(state.snapshot().map { it.getLabel() }, reopened.entries.map { it.getLabel() }) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { restore.close(); backupEntries.forEach { it.destroy() }; state.lock(); dir.deleteRecursively() }
    }

    @Test fun replaceIsExplicitAndRejectedAfterLock() {
        val dir = Files.createTempDirectory("compose-replace-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("vault-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val worker = Queue(); val ui = Queue()
        val restore = DesktopEncryptedRestore(state, worker, ui::execute, {}, BackupCoordinator(controller, state)::restoreEntries,
            decode = { _, _ -> arrayListOf(entry("replacement")) })
        try {
            EntryEditor(controller, state).add(entry("old"))
            restore.open(File("unused")); val request = restore.pending!!
            restore.submit(request, "p") { true }; worker.run(); ui.run()
            assertTrue(restore.confirm(request, true) { true })
            assertEquals(listOf("replacement"), state.snapshot().map { it.getLabel() })
            val reopened = controller.openVault("vault-password") as VaultController.Session.Loaded
            try { assertEquals(listOf("replacement"), reopened.entries.map { it.getLabel() }) }
            finally { DesktopUnlockOperation.discard(reopened) }
            restore.open(File("unused")); val locked = restore.pending!!
            state.lock()
            assertFalse(restore.submit(locked, "p") { true })
        } finally { restore.close(); state.lock(); dir.deleteRecursively() }
    }

    @Test fun cancelledQueuedResultsAreDestroyedBeforeUiDelivery() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val decoded = arrayListOf(entry("imported"))
        var installs = 0
        val restore = DesktopEncryptedRestore(state, worker, ui::execute, {}, { _, _ -> installs++; 0 }, { _, _ -> decoded })
        try {
            restore.open(File("unused")); val request = restore.pending!!
            restore.submit(request, "p") { true }; worker.run()
            restore.dismiss(request)
            assertTrue(decoded.isEmpty()) // queue retains only the empty task, not the entries
            ui.run()
            assertEquals(0, installs)
            assertFalse(restore.confirm(request, true) { true })
        } finally { restore.close(); state.lock() }
    }

    @Test fun cancellationDuringDecodeAndRejectedDispatchDestroyResults() {
        for (cancelDuringDecode in listOf(false, true)) {
            val state = state(); val worker = Queue(); val decoded = arrayListOf(entry("imported"))
            lateinit var restore: DesktopEncryptedRestore
            restore = DesktopEncryptedRestore(state, worker, { throw IllegalStateException() }, {}, { _, _ -> error("install") }, { _, _ ->
                if (cancelDuringDecode) restore.close()
                decoded
            })
            try {
                restore.open(File("unused")); restore.submit(restore.pending!!, "p") { true }
                worker.run(); assertTrue(decoded.isEmpty())
            } finally { restore.close(); state.lock() }
        }
    }

    @Test fun expiredDeliveryAndCancelledQueuedWorkNeverInstall() {
        val state = state(); val worker = Queue(); val ui = Queue()
        var decodes = 0; var active = true
        val decoded = arrayListOf(entry("imported"))
        val restore = DesktopEncryptedRestore(state, worker, ui::execute, {}, { _, _ -> error("install") }, { _, _ -> decodes++; decoded })
        try {
            restore.open(File("unused")); val cancelled = restore.pending!!
            restore.submit(cancelled, "p") { active }; restore.dismiss(cancelled); worker.run()
            assertEquals(0, decodes)
            restore.open(File("unused")); val request = restore.pending!!
            restore.submit(request, "p") { active }; worker.run(); active = false; ui.run()
            assertTrue(decoded.isEmpty())
            assertFalse(restore.confirm(request, true) { active })
        } finally { restore.close(); state.lock() }
    }

    @Test fun wrongPasswordMalformedEmptyAndDuplicateBackupsDoNotReachConfirmation() {
        val dir = Files.createTempDirectory("compose-invalid-restore-").toFile()
        val state = state(); val worker = Queue(); val ui = Queue()
        val restore = DesktopEncryptedRestore(state, worker, ui::execute, {}, { _, _ -> error("install") })
        val entries = arrayListOf(entry("same"), entry("same"))
        try {
            val source = File(dir, "backup.aes")
            for (document in listOf("[]", "not json", VaultJson.entriesToString(entries))) {
                source.writeBytes(EncryptedBackupCodec.seal("p", document))
                restore.open(source); val request = restore.pending!!
                restore.submit(request, "p") { true }; worker.run(); ui.run()
                assertTrue(request.failed); assertTrue(request.count == null)
                restore.dismiss(request)
            }
            source.writeBytes(EncryptedBackupCodec.seal("right", VaultJson.entriesToString(arrayListOf(entries[0]))))
            restore.open(source); val request = restore.pending!!
            restore.submit(request, "wrong") { true }; worker.run(); ui.run()
            assertTrue(request.failed); assertTrue(request.count == null)
        } finally { restore.close(); state.lock(); entries.forEach { it.destroy() }; dir.deleteRecursively() }
    }

    @Test fun failedPersistenceLeavesLiveStateUnchanged() {
        val dir = Files.createTempDirectory("compose-failed-restore-").toFile()
        val storage = DesktopStorage.open(dir)
        val controller = VaultController(storage)
        val loaded = controller.createVault("vault-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val worker = Queue(); val ui = Queue()
        val restore = DesktopEncryptedRestore(state, worker, ui::execute, {}, BackupCoordinator(controller, state)::restoreEntries,
            decode = { _, _ -> arrayListOf(entry("new")) })
        try {
            val existing = entry("old"); EntryEditor(controller, state).add(existing)
            restore.open(File("unused")); val request = restore.pending!!
            restore.submit(request, "p") { true }; worker.run(); ui.run()
            // Turning the storage directory into a file makes writes fail, even as root.
            dir.deleteRecursively(); dir.writeText("not a directory")
            assertFalse(restore.confirm(request, true) { true })
            assertTrue(request.failed)
            assertTrue(state.snapshot().single() === existing)
            assertEquals("old", existing.getLabel())
            assertTrue(request.count == null)
        } finally { restore.close(); state.lock(); dir.deleteRecursively() }
    }
}
