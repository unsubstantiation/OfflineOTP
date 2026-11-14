package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.OpenPgpPort
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ownership tests use an injected port, not real native OpenPGP cryptography. */
class DesktopOpenPgpImportTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private class Port(var document: String) : OpenPgpPort {
        var calls = 0
        var message: ByteArray? = null; var key: ByteArray? = null; var pass: CharArray? = null; var output: ByteArray? = null
        override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>): ByteArray = error("unused")
        override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?): ByteArray {
            calls++; this.message = message; key = secretKeyBlock; pass = passphrase
            require(passphrase?.concatToString() == "correct")
            return document.toByteArray().also { output = it }
        }
        fun wiped() {
            assertTrue(message!!.all { it == 0.toByte() }); assertTrue(key!!.all { it == 0.toByte() })
            assertTrue(pass!!.all { it == '\u0000' }); output?.let { assertTrue(it.all { byte -> byte == 0.toByte() }) }
        }
    }
    private fun harness(test: (File, VaultController, DesktopVaultState, Port) -> Unit) {
        val dir = Files.createTempDirectory("compose-pgp-import-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("vault-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val entry = ManualEntryInput(label = "imported", secret = "JBSWY3DPEHPK3PXP").build()
        val port = try { Port(VaultJson.entriesToString(listOf(entry))) } finally { entry.destroy() }
        try { test(dir, controller, state, port) } finally { state.lock(); dir.deleteRecursively() }
    }
    @Test fun explicitReplacementPersistsAndDestroysOnlyRemovedLiveEntries() = harness { dir, controller, state, port ->
        val original = ManualEntryInput(label = "old", secret = "JBSWY3DPEHPK3PXP").build()
        assertTrue(EntryEditor(controller, state).add(original))
        val source = File(dir, "backup.pgp").apply { writeText("message") }
        val key = File(dir, "secret.asc").apply { writeText("key") }
        val worker = Queue(); val ui = Queue()
        val importer = DesktopOpenPgpImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries, port,
            replaceAll = { BackupCoordinator(controller, state).restoreEntries(ArrayList(it), replace = true) })
        try {
            importer.open(source); val request = importer.pending!!
            importer.submit(request, DesktopOpenPgpImport.Input("correct", key)) { true }; worker.run(); ui.run()
            assertTrue(state.snapshot().single() === original)
            assertFalse(importer.confirmRestore(request, true) { false })
            assertTrue(original.getSecretSize() > 0)
            assertTrue(importer.confirmRestore(request, true) { true })
            assertEquals(0, original.getSecretSize())
            assertEquals("imported", state.snapshot().single().getLabel())
            assertFalse(importer.confirmRestore(request, true) { true })
            val reopened = controller.openVault("vault-password") as VaultController.Session.Loaded
            try { assertEquals(listOf("imported"), reopened.entries.map { it.getLabel() }) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { importer.close() }
    }
    @Test fun failedReplacementRetainsOriginalAndConsumesPreparedEntries() = harness { dir, controller, state, port ->
        val original = ManualEntryInput(label = "old", secret = "JBSWY3DPEHPK3PXP").build()
        assertTrue(EntryEditor(controller, state).add(original))
        val source = File(dir, "backup.pgp").apply { writeText("message") }
        val key = File(dir, "secret.asc").apply { writeText("key") }
        val worker = Queue(); val ui = Queue()
        val importer = DesktopOpenPgpImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries, port,
            replaceAll = { BackupCoordinator(controller, state).restoreEntries(ArrayList(it), replace = true) })
        try {
            importer.open(source); val request = importer.pending!!
            importer.submit(request, DesktopOpenPgpImport.Input("correct", key)) { true }; worker.run(); ui.run()
            val prepared = request.entries!!.single()
            dir.deleteRecursively(); dir.writeText("not a directory")
            assertFalse(importer.confirmRestore(request, true) { true })
            assertTrue(request.failed); assertTrue(request.count == null)
            assertEquals(0, prepared.getSecretSize())
            assertTrue(state.snapshot().single() === original); assertTrue(original.getSecretSize() > 0)
        } finally { importer.close() }
    }
    @Test fun replacementRequiresHostAuthorityAndMergeKeepsCurrentEntries() = harness { dir, controller, state, port ->
        val original = ManualEntryInput(label = "old", secret = "JBSWY3DPEHPK3PXP").build()
        assertTrue(EntryEditor(controller, state).add(original))
        val source = File(dir, "backup.pgp").apply { writeText("message") }
        val key = File(dir, "secret.asc").apply { writeText("key") }
        val worker = Queue(); val ui = Queue()
        val importer = DesktopOpenPgpImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries, port)
        try {
            importer.open(source); val request = importer.pending!!
            importer.submit(request, DesktopOpenPgpImport.Input("correct", key)) { true }; worker.run(); ui.run()
            assertFalse(importer.confirmRestore(request, true) { true })
            assertEquals(1, request.count)
            assertTrue(importer.confirmRestore(request, false) { true })
            assertEquals(2, state.snapshot().size)
            assertTrue(state.snapshot().any { it === original }); assertTrue(original.getSecretSize() > 0)
        } finally { importer.close() }
    }

    @Test fun requiresConfirmationPersistsAndWipesMutableBoundaryBuffers() = harness { dir, controller, state, port ->
        val source = File(dir, "backup.pgp").apply { writeText("message") }
        val key = File(dir, "secret.asc").apply { writeText("key") }
        val worker = Queue(); val ui = Queue()
        val importer = DesktopOpenPgpImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries, port)
        try {
            importer.open(source); val request = importer.pending!!
            val input = DesktopOpenPgpImport.Input("correct", key)
            assertFalse(input.toString().contains("correct"))
            importer.submit(request, input) { true }; worker.run(); port.wiped(); ui.run()
            assertEquals(1, request.count); assertTrue(state.snapshot().isEmpty())
            assertTrue(importer.confirm(request) { true })
            val reopened = controller.openVault("vault-password") as VaultController.Session.Loaded
            try { assertEquals("imported", reopened.entries.single().getLabel()) }
            finally { DesktopUnlockOperation.discard(reopened) }
            assertEquals("message", source.readText()); assertEquals("key", key.readText())
        } finally { importer.close() }
    }
    @Test fun missingKeyWrongPassphraseAndMalformedPayloadNeverInstall() = harness { dir, controller, state, port ->
        val source = File(dir, "backup.pgp").apply { writeText("message") }
        val key = File(dir, "secret.asc").apply { writeText("key") }
        val worker = Queue(); val ui = Queue()
        val importer = DesktopOpenPgpImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries, port)
        try {
            importer.open(source); val request = importer.pending!!
            importer.submit(request, DesktopOpenPgpImport.Input("correct", null)) { true }; worker.run(); ui.run()
            assertTrue(request.failed); assertEquals(0, port.calls)
            importer.submit(request, DesktopOpenPgpImport.Input("wrong", key)) { true }; worker.run(); ui.run()
            assertTrue(request.failed); port.wiped()
            for (document in listOf("[]", "not JSON", " ")) {
                port.document = document
                importer.submit(request, DesktopOpenPgpImport.Input("correct", key)) { true }; worker.run(); ui.run()
                assertTrue(request.failed); assertTrue(request.count == null); port.wiped()
            }
            assertTrue(state.snapshot().isEmpty())
        } finally { importer.close() }
    }
    @Test fun cancelBeforeWorkerAndAfterDeliveryPreventsAdoption() = harness { dir, controller, state, port ->
        val source = File(dir, "backup.pgp").apply { writeText("message") }
        val key = File(dir, "secret.asc").apply { writeText("key") }
        val worker = Queue(); val ui = Queue()
        val importer = DesktopOpenPgpImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries, port)
        importer.open(source); val old = importer.pending!!
        importer.submit(old, DesktopOpenPgpImport.Input("correct", key)) { true }; importer.dismiss(old); worker.run()
        assertEquals(0, port.calls)
        importer.open(source); val current = importer.pending!!
        importer.submit(current, DesktopOpenPgpImport.Input("correct", key)) { true }; worker.run(); ui.run()
        importer.close(); assertFalse(importer.confirm(current) { true }); assertTrue(state.snapshot().isEmpty())
        port.wiped()
    }
}
