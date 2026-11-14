package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.LinScpCredentials
import com.fairuniversal.offlineotp.Utilities.SshConnection
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLinScpExportTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
    private fun password() = Entry.passwordFields("alice", "site", listOf("url" to "https://site", "username" to "alice", "password" to "private"))
    @Test fun exportContainsOnlyDetachedCompatibleEntriesAndRoundTripsOnDisk() {
        val dir = Files.createTempDirectory("compose-linscp-export-").toFile()
        val state = state(); val worker = Queue(); val ui = Queue()
        val credentials = password(); credentials.setDetailsVisible(false)
        state.add(credentials)
        state.add(Entry.sshKey("public", "private key", "server", "SSH", arrayListOf("work"), SshConnection.of("server", 2222, "alice", "ssh-password")))
        state.add(ManualEntryInput(secret = "JBSWY3DPEHPK3PXP").build())
        state.add(ManualEntryInput(type = Entry.OTPType.NOTE, label = "note", secret = "not exported").build())
        state.add(Entry.passwordArchive("archive.txt", "not exported".toByteArray()))
        state.applySearch("no matches")
        val exporter = DesktopEncryptedExport(state, worker, ui::execute, {}, seal = DesktopLinScpExport::seal,
            snapshot = { DesktopLinScpExport.snapshot(state) })
        try {
            val target = File(dir, "linscp.aes")
            exporter.open(target); val request = exporter.pending!!
            assertTrue(exporter.submit(request, "export-password", "export-password"))
            credentials.setLabel("changed after snapshot")
            worker.run(); assertFalse(exporter.saved); ui.run(); assertTrue(exporter.saved)
            val bytes = target.readBytes()
            val imported = try { LinScpCredentials.importBytes(bytes, "export-password") } finally { bytes.fill(0) }
            try {
                assertEquals(2, imported.size)
                assertTrue(imported.all { it.isPassword() || it.isSshKey() })
                assertEquals("private", imported.first { it.isPassword() }.getPasswordFields().value("password"))
                assertEquals("alice", imported.first { it.isPassword() }.getLabel())
                assertEquals("private key", imported.first { it.isSshKey() }.getSshKeys().privateKey)
                assertEquals("ssh-password", imported.first { it.isSshKey() }.getSshConnection()!!.password)
                assertEquals("changed after snapshot", credentials.getLabel())
            } finally { imported.forEach { it.destroy() } }
        } finally { exporter.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun noCompatibleEntriesAndLockedStateCannotProduceSnapshot() {
        val state = state(); val worker = Queue(); val ui = Queue()
        state.add(ManualEntryInput(secret = "JBSWY3DPEHPK3PXP").build())
        val exporter = DesktopEncryptedExport(state, worker, ui::execute, {}, seal = DesktopLinScpExport::seal,
            snapshot = { DesktopLinScpExport.snapshot(state) })
        try {
            exporter.open(File("unused")); val request = exporter.pending!!
            assertFalse(exporter.submit(request, "p", "p"))
            assertEquals(DesktopEncryptedExport.Failure.EXPORT, request.failure)
            assertTrue(worker.tasks.isEmpty())
            state.lock(); var rejected = false
            try { DesktopLinScpExport.snapshot(state) } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
        } finally { exporter.close(); state.lock() }
    }
    @Test fun existingFilesAreNeverOverwrittenAndCanceledWorkDoesNotWrite() {
        val dir = Files.createTempDirectory("compose-linscp-existing-").toFile()
        val state = state(); state.add(password()); val worker = Queue(); val ui = Queue()
        val exporter = DesktopEncryptedExport(state, worker, ui::execute, {}, seal = DesktopLinScpExport::seal,
            snapshot = { DesktopLinScpExport.snapshot(state) })
        try {
            val target = File(dir, "exists.aes").apply { writeText("keep") }
            exporter.open(target); val request = exporter.pending!!
            exporter.submit(request, "p", "p"); worker.run(); ui.run()
            assertEquals(DesktopEncryptedExport.Failure.EXISTS, request.failure)
            assertEquals("keep", target.readText())
            exporter.dismiss(request)
            val canceled = File(dir, "canceled.aes")
            exporter.open(canceled); exporter.submit(exporter.pending!!, "p", "p"); exporter.close(); worker.run()
            assertFalse(canceled.exists())
        } finally { exporter.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun encoderRejectsUnexpectedKindsRatherThanSilentlyDroppingThem() {
        val note = ManualEntryInput(type = Entry.OTPType.NOTE, label = "note", secret = "not a credential").build()
        try {
            var rejected = false
            try { DesktopLinScpExport.seal("p", VaultJson.entriesToString(listOf(note))) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertTrue(note.getSecretSize() > 0)
        } finally { note.destroy() }
    }
}
