package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.PasswordImport
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPasswordImportTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private val csv = "name,url,username,password\nExample,https://example.com,alice,private-password\n"
    private fun harness(test: (File, VaultController, DesktopVaultState) -> Unit) {
        val dir = Files.createTempDirectory("compose-password-import-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("vault-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        try { test(dir, controller, state) } finally { state.lock(); dir.deleteRecursively() }
    }
    @Test fun bothModesRequireConfirmationPersistAndLeaveSourceUnchanged() = harness { dir, controller, state ->
        val source = File(dir, "passwords.csv").apply { writeText(csv) }
        val worker = Queue(); val ui = Queue()
        val importer = DesktopPasswordImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries)
        try {
            for (mode in PasswordImport.Mode.values()) {
                val before = state.snapshot().size
                importer.open(source); val request = importer.pending!!
                assertTrue(importer.submit(request, mode) { true })
                assertFalse(importer.submit(request, mode) { true })
                worker.run(); ui.run()
                assertEquals(1, request.count)
                assertEquals(before, state.snapshot().size)
                assertTrue(importer.confirm(request) { true })
                assertEquals(1, importer.importedCount)
                assertEquals(csv, source.readText())
            }
            val reopened = controller.openVault("vault-password") as VaultController.Session.Loaded
            try {
                assertEquals(2, reopened.entries.size)
                assertEquals("private-password", reopened.entries.first { it.isPassword() }.getPasswordFields().value("password"))
                assertEquals(csv, String(reopened.entries.first { it.isPasswordArchive() }.exportPasswordArchive(), Charsets.UTF_8))
            } finally { DesktopUnlockOperation.discard(reopened) }
            importer.open(source); val duplicate = importer.pending!!
            importer.submit(duplicate, PasswordImport.Mode.INDIVIDUAL) { true }; worker.run(); ui.run()
            assertTrue(importer.confirm(duplicate) { true }); assertEquals(0, importer.importedCount)
        } finally { importer.close() }
    }
    @Test fun cancelQueuedAndReadyResultsDestroysOwnedEntries() = harness { _, controller, state ->
        for (ready in listOf(false, true)) {
            val worker = Queue(); val ui = Queue()
            val entry = Entry.passwordFields("alice", "site", listOf("url" to "https://site", "username" to "alice", "password" to "secret"))
            val decoded = arrayListOf(entry)
            val importer = DesktopPasswordImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries, { _, _ -> decoded })
            importer.open(File("unused")); val request = importer.pending!!
            importer.submit(request, PasswordImport.Mode.INDIVIDUAL) { true }; worker.run()
            if (ready) ui.run()
            importer.dismiss(request)
            assertEquals(0, entry.getSecretSize()); assertTrue(decoded.isEmpty())
            if (!ready) ui.run()
            assertFalse(importer.confirm(request) { true }); assertTrue(state.snapshot().isEmpty())
            importer.close()
        }
    }
    @Test fun closedAndExpiredRequestsNeverInstallAndDropResults() = harness { _, controller, state ->
        val worker = Queue(); val ui = Queue(); var active = true
        val decoded = arrayListOf(Entry.passwordFields("alice", "site", listOf("url" to "https://site", "username" to "alice", "password" to "secret")))
        val importer = DesktopPasswordImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries, { _, _ -> decoded })
        importer.open(File("unused")); val request = importer.pending!!
        importer.submit(request, PasswordImport.Mode.INDIVIDUAL) { active }; worker.run(); active = false; ui.run()
        assertTrue(decoded.isEmpty()); assertFalse(importer.confirm(request) { active })
        importer.close(); importer.open(File("unused")); assertTrue(importer.pending == null)
    }
    @Test fun malformedFilesAndFailedWritesLeaveVaultUnchanged() = harness { dir, controller, state ->
        val worker = Queue(); val ui = Queue()
        val source = File(dir, "passwords.csv").apply { writeText("invalid csv") }
        val importer = DesktopPasswordImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries)
        try {
            importer.open(source); val request = importer.pending!!
            importer.submit(request, PasswordImport.Mode.INDIVIDUAL) { true }; worker.run(); ui.run()
            assertTrue(request.failed); assertTrue(request.count == null)
            source.writeText(csv)
            importer.submit(request, PasswordImport.Mode.INDIVIDUAL) { true }; worker.run(); ui.run()
            dir.deleteRecursively(); dir.writeText("not a directory")
            assertFalse(importer.confirm(request) { true })
            assertTrue(request.failed); assertTrue(request.count == null); assertTrue(state.snapshot().isEmpty())
        } finally { importer.close() }
    }
    @Test fun rejectedDeliveryAndCancellationDuringDecodeDestroyResults() = harness { _, controller, state ->
        for (cancel in listOf(false, true)) {
            val worker = Queue()
            val decoded = arrayListOf(Entry.passwordFields("alice", "site", listOf("url" to "https://site", "username" to "alice", "password" to "secret")))
            lateinit var importer: DesktopPasswordImport
            importer = DesktopPasswordImport(state, worker, { throw IllegalStateException() }, {}, EntryEditor(controller, state)::importEntries, { _, _ ->
                if (cancel) importer.close()
                decoded
            })
            importer.open(File("unused")); importer.submit(importer.pending!!, PasswordImport.Mode.ARCHIVE) { true }
            worker.run(); assertTrue(decoded.isEmpty()); assertTrue(state.snapshot().isEmpty()); importer.close()
        }
    }
}
