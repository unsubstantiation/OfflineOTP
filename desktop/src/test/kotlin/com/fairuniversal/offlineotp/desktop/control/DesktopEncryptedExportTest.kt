package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupCodec
import com.fairuniversal.offlineotp.desktop.storage.EncryptedBackupFile
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEncryptedExportTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runNext() = tasks.removeAt(0).run()
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES")).apply {
        add(ManualEntryInput(label = "alice", secret = "JBSWY3DPEHPK3PXP").build())
    }

    @Test
    fun encryptedFileRoundTripsTheDetachedSnapshotAndRequiresUiDeliveryForSuccess() {
        val directory = Files.createTempDirectory("compose-export-").toFile()
        val state = state(); val worker = Queue(); val ui = Queue()
        val exporter = DesktopEncryptedExport(state, worker, ui::execute, {})
        val target = File(directory, "backup.aes")
        try {
            exporter.open(target)
            val request = exporter.pending!!
            assertTrue(exporter.submit(request, "backup-password", "backup-password"))
            assertFalse(exporter.submit(request, "duplicate", "duplicate"))
            assertFalse(target.exists())
            state.snapshot().single().setLabel("changed after snapshot")
            worker.runNext()
            assertFalse(exporter.saved)
            val ciphertext = target.readBytes()
            val restored = VaultJson.stringToEntries(EncryptedBackupCodec.open("backup-password", ciphertext))
            try { assertEquals("alice", restored.single().getLabel()) }
            finally { restored.forEach { it.destroy() }; ciphertext.fill(0) }
            assertEquals("changed after snapshot", state.snapshot().single().getLabel())
            ui.runNext()
            assertTrue(exporter.saved)
            assertTrue(exporter.pending == null)
            if (Files.getFileStore(target.toPath()).supportsFileAttributeView("posix")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target.toPath()))
            }
        } finally { exporter.close(); state.lock(); directory.deleteRecursively() }
    }

    @Test
    fun existingDestinationIsNeverOverwrittenAndOutputBufferIsWiped() {
        val directory = Files.createTempDirectory("compose-export-existing-").toFile()
        val target = File(directory, "existing.aes").apply { writeText("keep this file") }
        val state = state(); val worker = Queue(); val ui = Queue()
        val bytes = byteArrayOf(1, 2, 3)
        val exporter = DesktopEncryptedExport(state, worker, ui::execute, {}, seal = { _, _ -> bytes })
        try {
            exporter.open(target); val request = exporter.pending!!
            exporter.submit(request, "password", "password")
            worker.runNext(); ui.runNext()
            assertEquals("keep this file", target.readText())
            assertEquals(DesktopEncryptedExport.Failure.EXISTS, request.failure)
            assertFalse(exporter.saved)
            assertTrue(bytes.all { it == 0.toByte() })
        } finally { exporter.close(); state.lock(); directory.deleteRecursively() }
    }

    @Test
    fun staleMismatchedDismissedAndLockedRequestsNeverStartWork() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val exporter = DesktopEncryptedExport(state, worker, ui::execute, {}, write = { _, _ -> error("unexpected write") })
        try {
            exporter.open(File("unused.aes")); val stale = exporter.pending!!
            exporter.open(File("unused-two.aes")); val request = exporter.pending!!
            assertFalse(exporter.submit(stale, "p", "p"))
            assertFalse(exporter.submit(request, "", ""))
            assertFalse(exporter.submit(request, "password", "typo"))
            assertEquals(DesktopEncryptedExport.Failure.PASSWORD, request.failure)
            exporter.dismiss(stale)
            assertTrue(exporter.pending === request)
            exporter.dismiss(request)
            assertFalse(exporter.submit(request, "p", "p"))
            exporter.open(File("unused.aes")); val fresh = exporter.pending!!
            state.lock()
            assertFalse(exporter.submit(fresh, "p", "p"))
            assertTrue(worker.tasks.isEmpty())
        } finally { exporter.close(); state.lock() }
    }

    @Test
    fun cancellationBeforeWorkerAndDuringEncryptionSuppressesWrites() {
        for (duringEncryption in listOf(false, true)) {
            val state = state(); val worker = Queue(); val ui = Queue()
            var encryptions = 0; var writes = 0
            val bytes = byteArrayOf(1, 2, 3)
            lateinit var exporter: DesktopEncryptedExport
            exporter = DesktopEncryptedExport(state, worker, ui::execute, {}, seal = { _, _ ->
                encryptions++; exporter.close(); bytes
            }, write = { _, _ -> writes++ })
            try {
                exporter.open(File("unused.aes"))
                exporter.submit(exporter.pending!!, "p", "p")
                if (!duringEncryption) exporter.close()
                worker.runNext()
                assertEquals(if (duringEncryption) 1 else 0, encryptions)
                assertEquals(0, writes)
                assertTrue(ui.tasks.isEmpty())
                if (duringEncryption) assertTrue(bytes.all { it == 0.toByte() })
            } finally { exporter.close(); state.lock() }
        }
    }

    @Test
    fun independentLockBeforeWorkerStartsPreventsEncryption() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val exporter = DesktopEncryptedExport(state, worker, ui::execute, {}, seal = { _, _ -> error("locked") })
        try {
            exporter.open(File("unused.aes")); exporter.submit(exporter.pending!!, "p", "p")
            state.lock(); worker.runNext()
            assertTrue(ui.tasks.isEmpty())
        } finally { exporter.close(); state.lock() }
    }

    @Test
    fun completedWriteAfterCancellationDoesNotPublishSuccess() {
        val state = state(); val worker = Queue(); val ui = Queue(); var writes = 0
        val bytes = byteArrayOf(1)
        val exporter = DesktopEncryptedExport(state, worker, ui::execute, {}, seal = { _, _ -> bytes }, write = { _, _ -> writes++ })
        try {
            exporter.open(File("unused.aes")); val request = exporter.pending!!
            exporter.submit(request, "p", "p"); worker.runNext()
            exporter.dismiss(request); ui.runNext()
            assertEquals(1, writes)
            assertFalse(exporter.saved)
            assertTrue(bytes.all { it == 0.toByte() })
        } finally { exporter.close(); state.lock() }
    }

    @Test
    fun failedOrRejectedWorkReportsGenericFailureAndPermitsFreshInput() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val bytes = byteArrayOf(5)
        val exporter = DesktopEncryptedExport(state, worker, ui::execute, {}, seal = { _, _ -> bytes },
            write = { _, _ -> throw java.io.IOException("must not expose path or password") })
        try {
            exporter.open(File("unused.aes")); val request = exporter.pending!!
            exporter.submit(request, "p", "p"); worker.runNext(); ui.runNext()
            assertFalse(request.busy)
            assertEquals(DesktopEncryptedExport.Failure.EXPORT, request.failure)
            assertTrue(bytes.all { it == 0.toByte() })
            assertTrue(exporter.submit(request, "retry", "retry"))
            exporter.dismiss(request); worker.runNext()
            val rejected = DesktopEncryptedExport(state, Executor { throw RejectedExecutionException() }, ui::execute, {})
            rejected.open(File("unused.aes")); val failure = rejected.pending!!
            rejected.submit(failure, "p", "p")
            assertFalse(failure.busy)
            assertEquals(DesktopEncryptedExport.Failure.EXPORT, failure.failure)
            rejected.close()
        } finally { exporter.close(); state.lock() }
    }
}
