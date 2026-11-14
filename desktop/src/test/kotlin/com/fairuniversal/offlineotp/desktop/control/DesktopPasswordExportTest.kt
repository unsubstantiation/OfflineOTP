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

class DesktopPasswordExportTest {
    private class Queue : Executor {
        val tasks = arrayListOf<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")).also { state ->
        for (extra in listOf("note", "provider")) state.add(Entry.passwordFields("alice", "site",
            listOf("name" to "site", "url" to "https://site", "username" to "alice", "password" to "secret", extra to "preserved")))
    }
    @Test fun selectionNeedsCurrentExplicitConfirmationAndWritesOnlyChosenGroup() {
        val dir = Files.createTempDirectory("password-export-").toFile()
        val state = state(); val worker = Queue(); val ui = Queue()
        val export = DesktopPasswordExport(state, worker, ui::execute, {})
        try {
            assertTrue(export.open { true }); val request = export.pending!!
            val file = File(dir, "passwords.csv")
            assertFalse(export.confirm(request) { true }); assertTrue(worker.tasks.isEmpty())
            assertFalse(export.choose(request, 2, file) { true })
            assertTrue(export.choose(request, 1, file) { true }); assertFalse(file.exists())
            assertFalse(export.confirm(request) { false }); assertTrue(export.confirm(request) { true })
            assertFalse(export.confirm(request) { true })
            assertTrue(request.groups.all { it.takeBytes() == null })
            worker.run(); ui.run()
            assertEquals(DesktopPasswordExport.Status.SAVED, request.status)
            assertTrue(file.readText().startsWith("name,url,username,password,provider\n"))
            assertTrue(file.readText().contains("secret,preserved"))
        } finally { export.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun cancellationStalenessAndLockSuppressQueuedWrites() {
        val state = state(); val worker = Queue(); val ui = Queue(); var writes = 0
        val export = DesktopPasswordExport(state, worker, ui::execute, {}, write = { _, _ -> writes++ })
        try {
            export.open { true }; val old = export.pending!!; export.dismiss(old)
            assertTrue(old.groups.all { it.takeBytes() == null })
            export.open { true }; val next = export.pending!!
            assertFalse(export.choose(old, 0, File("unused")) { true })
            export.choose(next, 0, File("unused")) { true }; export.confirm(next) { true }
            export.close(); worker.run(); assertEquals(0, writes)
        } finally { export.close(); state.lock() }
    }
    @Test fun existingFileIsUntouchedAndLateCompletionCannotReviveDialog() {
        val dir = Files.createTempDirectory("password-existing-").toFile()
        val state = state(); val worker = Queue(); val ui = Queue()
        val export = DesktopPasswordExport(state, worker, ui::execute, {})
        try {
            val file = File(dir, "existing").apply { writeText("keep") }
            export.open { true }; val request = export.pending!!
            export.choose(request, 0, file) { true }; export.confirm(request) { true }; worker.run(); ui.run()
            assertEquals(DesktopPasswordExport.Status.EXISTS, request.status); assertEquals("keep", file.readText())
            export.dismiss(request); export.open { true }; val next = export.pending!!
            export.choose(next, 0, File(dir, "new")) { true }; export.confirm(next) { true }; worker.run()
            export.dismiss(next); ui.run(); assertTrue(export.pending == null)
        } finally { export.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun outputIsWipedOnSuccessFailureAndRejectedDelivery() {
        for (fail in listOf(false, true)) {
            val state = state(); val worker = Queue(); var captured: ByteArray? = null
            val export = DesktopPasswordExport(state, worker, { throw RejectedExecutionException() }, {}, write = { _, bytes ->
                captured = bytes; if (fail) error("write failure")
            })
            try {
                export.open { true }; val request = export.pending!!
                export.choose(request, 0, File("unused")) { true }; export.confirm(request) { true }; worker.run()
                assertTrue(captured!!.all { it == 0.toByte() })
            } finally { export.close(); state.lock() }
        }
    }
    @Test fun lockingBeforeWorkerAndExpiryAtDeliverySuppressCompletion() {
        val state = state(); val worker = Queue(); val ui = Queue(); var writes = 0; var active = true
        val export = DesktopPasswordExport(state, worker, ui::execute, {}, write = { _, _ -> writes++ })
        try {
            export.open { true }; val first = export.pending!!
            export.choose(first, 0, File("unused")) { true }; export.confirm(first) { active }
            worker.run(); active = false; ui.run()
            assertEquals(DesktopPasswordExport.Status.WRITING, first.status)
            export.dismiss(first); export.open { true }; val next = export.pending!!
            export.choose(next, 0, File("unused")) { true }; export.confirm(next) { true }
            state.lock(); worker.run(); assertEquals(1, writes)
        } finally { export.close(); state.lock() }
    }

    @Test fun expiredAndReentrantGuardsCannotAuthorizeAndRejectedWorkerReleasesPlan() {
        val state = state(); val ui = Queue()
        val export = DesktopPasswordExport(state, Executor { throw RejectedExecutionException() }, ui::execute, {})
        try {
            assertFalse(export.open { false })
            export.open { true }; val request = export.pending!!
            assertFalse(export.choose(request, 0, File("unused")) { export.dismiss(request); true })
            export.open { true }; val next = export.pending!!
            export.choose(next, 0, File("unused")) { true }; export.confirm(next) { true }
            assertEquals(DesktopPasswordExport.Status.FAILED, next.status)
            assertTrue(next.groups.all { it.takeBytes() == null })
            export.dismiss(next); state.lock(); assertFalse(export.open { true })
        } finally { export.close(); state.lock() }
    }
}
