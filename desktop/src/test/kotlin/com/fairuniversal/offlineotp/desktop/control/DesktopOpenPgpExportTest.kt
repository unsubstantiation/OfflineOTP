package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.OpenPgpPort
import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Injected-port orchestration tests; these do not exercise native cryptography. */
class DesktopOpenPgpExportTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private class Port : OpenPgpPort {
        var plain: ByteArray? = null; var key: ByteArray? = null; var output: ByteArray? = null
        var keys = emptyList<ByteArray>(); var received = emptyList<String>()
        var calls = 0; var fail = false; var afterEncrypt: () -> Unit = {}
        override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>): ByteArray {
            calls++; plain = plaintext; key = recipientKeyBlocks.first(); keys = recipientKeyBlocks.toList(); received = recipientKeyBlocks.map { String(it) }
            if (fail) throw IllegalArgumentException("private diagnostic")
            return plaintext.reversedArray().also { output = it; afterEncrypt() }
        }
        override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?) = message.reversedArray()
        fun wiped() {
            assertTrue(plain!!.all { it == 0.toByte() }); keys.forEach { value -> assertTrue(value.all { it == 0.toByte() }) }
            output?.let { assertTrue(it.all { byte -> byte == 0.toByte() }) }
        }
    }
    private fun harness(test: (File, DesktopVaultState, Queue, Queue, Port, DesktopOpenPgpExport) -> Unit) {
        val dir = Files.createTempDirectory("compose-pgp-export-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
        state.add(ManualEntryInput(secret = "JBSWY3DPEHPK3PXP", label = "original").build())
        val worker = Queue(); val ui = Queue(); val port = Port()
        val exporter = DesktopOpenPgpExport(state, worker, ui::execute, {}, port)
        try { test(dir, state, worker, ui, port, exporter) }
        finally { exporter.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun multipleRecipientFilesAreSnapshottedInOrderAndAllBuffersWiped() = harness { dir, _, worker, ui, port, exporter ->
        val first = File(dir, "first.asc").apply { writeText("first recipient") }
        val second = File(dir, "second.asc").apply { writeText("second recipient") }
        val selected = arrayListOf(first, second)
        val target = File(dir, "multiple.gpg")
        exporter.open(target); val request = exporter.pending!!
        assertTrue(exporter.submitRecipients(request, selected)); selected.clear()
        worker.run(); ui.run()
        assertEquals(listOf("first recipient", "second recipient"), port.received)
        assertTrue(exporter.saved); assertTrue(target.exists()); port.wiped()
    }
    @Test fun invalidSelectionAndExpiredAuthorityNeverQueueEncryption() = harness { dir, _, worker, _, port, exporter ->
        val key = File(dir, "key.asc").apply { writeText("key") }
        exporter.open(File(dir, "out.gpg")); val request = exporter.pending!!
        assertFalse(exporter.submitRecipients(request, emptyList()))
        assertFalse(exporter.submitRecipients(request, listOf(key, key)))
        assertFalse(exporter.submitRecipients(request, (0..8).map { File(dir, "$it.asc") }))
        assertFalse(exporter.submitRecipients(request, listOf(key)) { false })
        assertEquals(0, worker.tasks.size); assertEquals(0, port.calls)
    }
    @Test fun oneUnreadableOrEmptyRecipientFailsTheWholeExport() = harness { dir, _, worker, ui, port, exporter ->
        val first = File(dir, "first.asc").apply { writeText("first") }
        val second = File(dir, "second.asc")
        val target = File(dir, "out.gpg")
        exporter.open(target); val request = exporter.pending!!
        exporter.submitRecipients(request, listOf(first, second)); worker.run(); ui.run()
        assertEquals(DesktopOpenPgpExport.Failure.EXPORT, request.failure); assertEquals(0, port.calls)
        second.writeText("")
        exporter.submitRecipients(request, listOf(first, second)); worker.run(); ui.run()
        assertEquals(0, port.calls); assertFalse(target.exists())
    }
    @Test fun combinedSizeLimitIsEnforcedBeforeCallingProvider() = harness { dir, _, worker, ui, port, exporter ->
        val first = File(dir, "first.asc").apply { writeText("first") }
        val second = File(dir, "large.asc")
        java.io.RandomAccessFile(second, "rw").use { it.setLength(com.fairuniversal.offlineotp.Utilities.BoundedInputReader.MAX_DOCUMENT_BYTES.toLong()) }
        val target = File(dir, "out.gpg")
        exporter.open(target); val request = exporter.pending!!
        exporter.submitRecipients(request, listOf(first, second)); worker.run(); ui.run()
        assertEquals(DesktopOpenPgpExport.Failure.EXPORT, request.failure)
        assertEquals(0, port.calls); assertFalse(target.exists())
    }
    @Test fun multiRecipientProviderFailureWipesEveryKeyAndExpiredDeliveryDoesNotPublish() = harness { dir, _, worker, ui, port, exporter ->
        val keys = listOf("a", "b").map { name -> File(dir, name).apply { writeText(name) } }
        exporter.open(File(dir, "out.gpg")); val request = exporter.pending!!
        port.fail = true
        exporter.submitRecipients(request, keys); worker.run(); ui.run(); port.wiped()
        assertEquals(DesktopOpenPgpExport.Failure.EXPORT, request.failure)
        port.fail = false; var active = true
        exporter.submitRecipients(request, keys) { active }; worker.run(); active = false; ui.run()
        assertFalse(exporter.saved); port.wiped()
    }

    @Test fun writesDetachedSnapshotOnlyAfterExplicitSubmissionAndWipesBuffers() = harness { dir, state, worker, ui, port, exporter ->
        val key = File(dir, "public.asc").apply { writeText("recipient") }
        val target = File(dir, "backup.gpg")
        exporter.open(target); val request = exporter.pending!!
        assertFalse(target.exists())
        assertTrue(exporter.submit(request, key)); assertFalse(exporter.submit(request, key))
        state.snapshot().single().setLabel("changed")
        worker.run(); assertFalse(exporter.saved); ui.run(); assertTrue(exporter.saved)
        val decoded = VaultJson.stringToEntries(String(target.readBytes().reversedArray(), Charsets.UTF_8))
        try { assertEquals("original", decoded.single().getLabel()) } finally { decoded.forEach { it.destroy() } }
        port.wiped(); assertEquals("recipient", key.readText())
    }
    @Test fun missingAndEmptyKeysOrProviderFailureDoNotWrite() = harness { dir, _, worker, ui, port, exporter ->
        val target = File(dir, "backup.gpg")
        exporter.open(target); val request = exporter.pending!!
        assertFalse(exporter.submit(request, null)); assertEquals(DesktopOpenPgpExport.Failure.KEY, request.failure)
        val empty = File(dir, "empty.asc").apply { writeText("") }
        exporter.submit(request, empty); worker.run(); ui.run()
        assertEquals(DesktopOpenPgpExport.Failure.EXPORT, request.failure); assertEquals(0, port.calls)
        val key = File(dir, "public.asc").apply { writeText("key") }; port.fail = true
        exporter.submit(request, key); worker.run(); ui.run()
        assertEquals(DesktopOpenPgpExport.Failure.EXPORT, request.failure); assertFalse(target.exists()); port.wiped()
    }
    @Test fun existingFilesAndCancellationDuringEncryptionNeverOverwriteOrWrite() = harness { dir, _, worker, ui, port, exporter ->
        val key = File(dir, "public.asc").apply { writeText("key") }
        val existing = File(dir, "existing.gpg").apply { writeText("keep") }
        exporter.open(existing); val request = exporter.pending!!
        exporter.submit(request, key); worker.run(); ui.run()
        assertEquals(DesktopOpenPgpExport.Failure.EXISTS, request.failure); assertEquals("keep", existing.readText())
        exporter.dismiss(request)
        val canceled = File(dir, "canceled.gpg")
        exporter.open(canceled); port.afterEncrypt = { exporter.close() }
        exporter.submit(exporter.pending!!, key); worker.run()
        assertFalse(canceled.exists()); assertFalse(exporter.saved); assertTrue(ui.tasks.isEmpty()); port.wiped()
    }
    @Test fun closeBeforeWorkerAndBeforeDeliverySuppressesLateSuccess() = harness { dir, _, worker, ui, _, exporter ->
        val key = File(dir, "public.asc").apply { writeText("key") }
        val target = File(dir, "backup.gpg")
        exporter.open(target); val old = exporter.pending!!
        exporter.submit(old, key); exporter.dismiss(old); worker.run(); assertFalse(target.exists())
        exporter.open(target); exporter.submit(exporter.pending!!, key); worker.run()
        exporter.close(); ui.run(); assertFalse(exporter.saved)
        assertFalse(exporter.submit(old, key))
    }
}
