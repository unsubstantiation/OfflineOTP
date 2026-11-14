package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.LinScpCredentials
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLinScpImportTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private val ciphertext = "lscp1\$EBESExQVFhcYGRobHB0eH2O4ezzmYH370G39JNEWV3UKmNy5iaxBwNro1exoTvVdvdwtDnFxSYetybMyM3aAIA=="
    private fun ini(password: String) = "[Staging]\nprotocol=sftp\nhost=staging.example.org\nport=2200\nusername=ubuntu\npassword=$password\n"
    private fun harness(test: (File, VaultController, DesktopVaultState) -> Unit) {
        val dir = Files.createTempDirectory("compose-linscp-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("vault-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        try { test(dir, controller, state) } finally { state.lock(); dir.deleteRecursively() }
    }
    @Test fun sessionKeyBundleAndEncryptedVaultImportOnlyAfterConfirmation() {
        for (format in 0..2) harness { dir, controller, state ->
            val master = ByteArray(32) { it.toByte() }
            val keyFile = File(dir, "sessions.key").apply { writeBytes(master) }
            val source = File(dir, "input")
            val bytes = when (format) {
                0 -> ini(ciphertext).toByteArray()
                1 -> JSONObject().put("version", 1).put("sessions_ini", ini(ciphertext))
                    .put("sessions_key_b64", Base64.getEncoder().encodeToString(master)).toString().toByteArray()
                else -> {
                    val entries = LinScpCredentials.importBytes(ini("private").toByteArray())
                    try { LinScpCredentials.exportVault(entries, "backup-password") }
                    finally { entries.forEach { it.destroy() } }
                }
            }
            source.writeBytes(bytes)
            val worker = Queue(); val ui = Queue()
            val importer = DesktopLinScpImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries)
            try {
                importer.open(source); val request = importer.pending!!
                val input = DesktopLinScpImport.Input(if (format == 2) "backup-password" else "", if (format == 0) keyFile else null)
                assertFalse(input.toString().contains("backup-password"))
                importer.submit(request, input) { true }; worker.run(); ui.run()
                assertEquals(1, request.count); assertTrue(state.snapshot().isEmpty())
                assertTrue(importer.confirm(request) { true })
                val reopened = controller.openVault("vault-password") as VaultController.Session.Loaded
                try {
                    assertTrue(reopened.entries.single().isPassword())
                    assertEquals(if (format == 2) "private" else "S3cr3t!Pass#2026",
                        reopened.entries.single().getPasswordFields().value("password"))
                } finally { DesktopUnlockOperation.discard(reopened) }
                assertTrue(bytes.contentEquals(source.readBytes()))
                assertTrue(master.contentEquals(keyFile.readBytes()))
            } finally { importer.close(); bytes.fill(0); master.fill(0) }
        }
    }
    @Test fun missingWrongAndOversizedMasterKeysDoNotPrepareEntries() = harness { dir, controller, state ->
        val source = File(dir, "sessions.ini").apply { writeText(ini(ciphertext)) }
        val wrong = File(dir, "wrong.key").apply { writeBytes(ByteArray(32) { 99 }) }
        val oversized = File(dir, "large.key").apply { writeBytes(ByteArray(4097)) }
        val worker = Queue(); val ui = Queue()
        val importer = DesktopLinScpImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries)
        try {
            importer.open(source); val request = importer.pending!!
            for (key in listOf(null, wrong, oversized)) {
                importer.submit(request, DesktopLinScpImport.Input("", key)) { true }; worker.run(); ui.run()
                assertTrue(request.failed); assertTrue(request.count == null); assertTrue(state.snapshot().isEmpty())
            }
        } finally { importer.close() }
    }
    @Test fun wrongVaultPasswordCanRetryAndUnusedSessionKeyIsNotRead() = harness { dir, controller, state ->
        val entries = LinScpCredentials.importBytes(ini("private").toByteArray())
        val bytes = try { LinScpCredentials.exportVault(entries, "correct") } finally { entries.forEach { it.destroy() } }
        val source = File(dir, "vault.aes").apply { writeBytes(bytes) }; bytes.fill(0)
        val worker = Queue(); val ui = Queue()
        val importer = DesktopLinScpImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries)
        try {
            importer.open(source); val request = importer.pending!!
            importer.submit(request, DesktopLinScpImport.Input("wrong")) { true }; worker.run(); ui.run()
            assertTrue(request.failed)
            importer.submit(request, DesktopLinScpImport.Input("correct", File(dir, "missing.key"))) { true }; worker.run(); ui.run()
            assertFalse(request.failed); assertEquals(1, request.count)
            importer.dismiss(request); assertTrue(state.snapshot().isEmpty())
        } finally { importer.close() }
    }
    @Test fun cancelledQueuedInputsAndLateResultsAreDiscarded() = harness { _, controller, state ->
        val worker = Queue(); val ui = Queue(); var decodes = 0
        val entries = LinScpCredentials.importBytes(ini("private").toByteArray())
        val entry = entries.single()
        val importer = DesktopLinScpImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries,
            decode = { _, _ -> decodes++; entries })
        importer.open(File("unused")); val old = importer.pending!!
        importer.submit(old, DesktopLinScpImport.Input("never-read")) { true }; importer.dismiss(old); worker.run()
        assertEquals(0, decodes)
        importer.open(File("unused")); val current = importer.pending!!
        assertFalse(importer.submit(old, DesktopLinScpImport.Input("stale")) { true })
        importer.submit(current, DesktopLinScpImport.Input("secret")) { true }; worker.run()
        importer.close(); ui.run()
        assertEquals(0, entry.getSecretSize()); assertTrue(entries.isEmpty()); assertTrue(state.snapshot().isEmpty())
    }
}
