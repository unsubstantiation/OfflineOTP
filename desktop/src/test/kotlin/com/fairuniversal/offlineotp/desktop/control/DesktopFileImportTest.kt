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
