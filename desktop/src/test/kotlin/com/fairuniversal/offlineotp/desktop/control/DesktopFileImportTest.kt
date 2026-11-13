package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.io.File
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopFileImportTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { tasks.removeAt(0).run() }
    }
    @Test fun expiredDeliveryDestroysDecodedEntriesAndDismissesBusyRequest() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val worker = Queue(); val ui = Queue()
        val decoded = arrayListOf(ManualEntryInput(label = "alice", secret = "JBSWY3DPEHPK3PXP").build())
        val importer = DesktopFileImport<Unit>(state, worker, { ui.execute(it) }, {}, { error("unexpected commit") }, { _, _ -> decoded })
        try {
            importer.open(File("old")); val request = importer.pending!!; var live = true
            importer.submit(request, Unit) { live }; worker.run(); live = false; ui.run()
            assertTrue(decoded.isEmpty()); assertTrue(importer.pending == null)
            assertTrue(request.count == null); assertTrue(importer.importedCount == null)
        } finally { importer.close(); state.lock() }
    }
    @Test fun deliveryGuardReplacementPreservesNewWorkAndDestroysOldResult() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val worker = Queue(); val ui = Queue()
        val batches = ArrayList<ArrayList<Entry>>()
        val importer = DesktopFileImport<Unit>(state, worker, { ui.execute(it) }, {}, { entries -> entries.forEach { it.destroy() }; 1 },
            { _, _ -> arrayListOf(ManualEntryInput(label = "alice", secret = "JBSWY3DPEHPK3PXP").build()).also { batches.add(it) } })
        try {
            importer.open(File("old")); val old = importer.pending!!; var checks = 0
            importer.submit(old, Unit) {
                if (++checks == 2) {
                    importer.dismiss(old); importer.open(File("new"))
                    importer.submit(importer.pending!!, Unit) { true }
                }
                true
            }
            worker.run(); ui.run()
            val fresh = importer.pending!!
            assertTrue(fresh !== old); assertTrue(fresh.busy); assertTrue(batches[0].isEmpty())
            worker.run(); ui.run()
            assertFalse(fresh.busy); assertEquals(1, fresh.count)
            assertTrue(importer.confirm(fresh) { true })
        } finally { importer.close(); state.lock() }
    }
    @Test fun submissionGuardCannotOverwriteReentrantSubmission() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val worker = Queue(); val ui = Queue()
        var decodes = 0
        val importer = DesktopFileImport<Unit>(state, worker, { ui.execute(it) }, {}, { 0 }, { _, _ -> decodes++; arrayListOf() })
        try {
            importer.open(File("old")); val request = importer.pending!!
            assertFalse(importer.submit(request, Unit) { assertTrue(importer.submit(request, Unit) { true }); true })
            assertEquals(1, worker.tasks.size); worker.run(); ui.run()
            assertEquals(1, decodes); assertEquals(0, request.count); assertFalse(request.failed)
        } finally { importer.close(); state.lock() }
    }
    private fun testImport(commit: (List<Entry>) -> Int?, test: (DesktopFileImport<Unit>, DesktopVaultState) -> Unit) {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val importer = DesktopFileImport<Unit>(state, Executor { it.run() }, { it.run() }, {}, commit,
            { _, _ -> arrayListOf(ManualEntryInput(label = "alice", secret = "JBSWY3DPEHPK3PXP").build()) })
        try { test(importer, state) } finally { importer.close(); state.lock() }
    }
    private fun ready(importer: DesktopFileImport<Unit>): DesktopFileImport.Request {
        importer.open(File("unused")); val request = importer.pending!!
        assertTrue(importer.submit(request, Unit) { true }); assertEquals(1, request.count)
        return request
    }
    @Test fun postCommitExpirySuppressesSuccessFailureAndExceptionNotices() {
        for (outcome in 0..2) {
            var commits = 0
            testImport({ entries ->
                commits++; entries.forEach { it.destroy() }
                when (outcome) { 0 -> 1; 1 -> null; else -> error("save failed") }
            }) { importer, state ->
                val request = ready(importer); var checks = 0
                assertFalse(importer.confirm(request) {
                    if (++checks == 2) { importer.close(); state.lock(); false } else true
                })
                assertEquals(1, commits); assertEquals(2, checks)
                assertTrue(importer.importedCount == null); assertTrue(importer.pending == null)
                assertFalse(request.failed); assertTrue(request.count == null)
            }
        }
    }
    @Test fun expiryWithoutOwnerClosureDismissesConsumedRequest() = testImport({ entries -> entries.forEach { it.destroy() }; 1 }) { importer, _ ->
        val request = ready(importer); var checks = 0
        assertFalse(importer.confirm(request) { ++checks == 1 })
        assertTrue(importer.pending == null); assertTrue(importer.importedCount == null)
    }
    @Test fun reentrantGuardsCannotCommitOldRequestOrOverwriteNewOne() {
        var commits = 0
        testImport({ entries -> commits++; entries.forEach { it.destroy() }; 1 }) { importer, _ ->
            val old = ready(importer)
            assertFalse(importer.confirm(old) { importer.dismiss(old); importer.open(File("new")); true })
            assertEquals(0, commits)
            importer.dismiss(importer.pending!!)
            val next = ready(importer); var checks = 0
            assertFalse(importer.confirm(next) {
                if (++checks == 2) { importer.dismiss(next); importer.open(File("newer")) }; true
            })
            assertEquals(1, commits); assertTrue(importer.pending !== next)
            assertTrue(importer.pending != null); assertTrue(importer.importedCount == null)
        }
    }
    @Test fun liveOutcomesRetainSuccessAndRetryBehavior() {
        for (success in listOf(false, true)) testImport({ entries -> entries.forEach { it.destroy() }; if (success) 1 else null }) { importer, _ ->
            val request = ready(importer)
            assertEquals(success, importer.confirm(request) { true })
            if (success) { assertEquals(1, importer.importedCount); assertTrue(importer.pending == null) }
            else { assertTrue(importer.pending === request); assertTrue(request.failed); assertTrue(request.count == null) }
        }
    }
}
