package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopArchiveExportTest {
    private class Queue : Executor {
        val tasks = arrayListOf<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private val content = byteArrayOf(0, -1, 13, 10, 34, 44, 61, 42)
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")).also {
        it.add(Entry.passwordArchive("../untrusted.csv", content))
        it.add(Entry.passwordFields("alice", "site", listOf("url" to "site", "username" to "alice", "password" to "secret")))
        it.applySearch("no matches")
    }
    @Test fun exactBytesAreWrittenOnlyAfterConfirmationToExplicitDestination() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val dir = Files.createTempDirectory("archive-export-").toFile()
        val export = DesktopArchiveExport(state, worker, ui::execute, {})
        try {
            export.open { true }; val request = export.pending!!
            assertEquals(listOf("../untrusted.csv"), request.labels)
            assertFalse(export.confirm(request) { true })
            assertFalse(export.choose(request, 1, File(dir, "bad")) { true })
            val target = File(dir, "selected.bin")
            assertTrue(export.choose(request, 0, target) { true })
            assertFalse(target.exists()); assertTrue(worker.tasks.isEmpty())
            assertFalse(export.confirm(request) { false })
            assertTrue(export.confirm(request) { true }); assertFalse(export.confirm(request) { true })
            state.snapshot().first { it.isPasswordArchive() }.destroy()
            worker.run(); ui.run()
            assertEquals(DesktopArchiveExport.Status.SAVED, request.status)
            assertTrue(content.contentEquals(target.readBytes()))
        } finally { export.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun replacedEntryDismissedAndExpiredRequestsCannotWrite() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val export = DesktopArchiveExport(state, worker, ui::execute, {})
        try {
            export.open { true }; val old = export.pending!!; export.dismiss(old)
            export.open { true }; val next = export.pending!!
            assertFalse(export.choose(old, 0, File("unused")) { true })
            export.choose(next, 0, File("unused")) { true }
            val original = state.snapshot().first { it.isPasswordArchive() }
            state.replace(original, Entry.passwordArchive(original.getLabel(), content))
            assertFalse(export.confirm(next) { true }); assertTrue(export.pending == null)
            export.open { true }; val third = export.pending!!
            export.choose(third, 0, File("unused")) { true }
            assertFalse(export.confirm(third) { export.dismiss(third); true })
            assertTrue(worker.tasks.isEmpty())
        } finally { export.close(); state.lock() }
    }
    @Test fun canceledQueuedWriteDoesNotRunAndLockedVaultCannotOpen() {
        val state = state(); val worker = Queue(); val ui = Queue(); var writes = 0
        val export = DesktopArchiveExport(state, worker, ui::execute, {}, write = { _, _ -> writes++ })
        try {
            export.open { true }; val request = export.pending!!
            export.choose(request, 0, File("unused")) { true }; export.confirm(request) { true }
            export.close(); worker.run(); assertEquals(0, writes)
            assertFalse(export.open { true })
            state.lock()
            assertFalse(DesktopArchiveExport(state, worker, ui::execute, {}).open { true })
        } finally { export.close(); state.lock() }
    }
    @Test fun existingDestinationIsNeverOverwritten() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val dir = Files.createTempDirectory("archive-existing-").toFile()
        val export = DesktopArchiveExport(state, worker, ui::execute, {})
        try {
            val target = File(dir, "existing").apply { writeText("keep") }
            export.open { true }; val request = export.pending!!
            export.choose(request, 0, target) { true }; export.confirm(request) { true }; worker.run(); ui.run()
            assertEquals(DesktopArchiveExport.Status.EXISTS, request.status); assertEquals("keep", target.readText())
        } finally { export.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun successFailureAndRejectedDispatchWipeBuffersAndSuppressLateResults() {
        for (fail in listOf(false, true)) {
            val state = state(); val worker = Queue(); val ui = Queue(); var data: ByteArray? = null
            val export = DesktopArchiveExport(state, worker, ui::execute, {}, write = { _, bytes -> data = bytes; if (fail) error("write failed") })
            try {
                export.open { true }; val request = export.pending!!
                export.choose(request, 0, File("unused")) { true }; export.confirm(request) { true }; worker.run()
                assertTrue(data!!.all { it == 0.toByte() })
                export.dismiss(request); export.open { true }; val next = export.pending!!; ui.run()
                assertTrue(export.pending === next); assertEquals(DesktopArchiveExport.Status.SELECT, next.status)
                export.dismiss(next)
                val rejected = DesktopArchiveExport(state, worker, { throw RejectedExecutionException() }, {}, write = { _, bytes -> data = bytes })
                try {
                    rejected.open { true }; val r = rejected.pending!!
                    rejected.choose(r, 0, File("unused")) { true }; rejected.confirm(r) { true }; worker.run()
                    assertTrue(data!!.all { it == 0.toByte() })
                } finally { rejected.close() }
            } finally { export.close(); state.lock() }
        }
    }
}
