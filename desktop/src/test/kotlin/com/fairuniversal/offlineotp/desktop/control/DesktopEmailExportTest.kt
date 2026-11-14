package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.ui.vault.EmailEntryInput
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEmailExportTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
    private fun email(): Entry {
        val original = EmailEntryInput(address = "alice@example.com", incomingHost = "imap.example.com",
            outgoingHost = "smtp.example.com", incomingPassword = "incoming", outgoingPassword = "outgoing").build()
        try {
            val account = original.getEmailAccount()
            account.account.put("provider_extra", "preserved")
            val extra = org.json.JSONObject(account.identity.toString()).put("email", "alias@example.com").put("password", "alias-secret")
            return Entry.email(account.account, account.identity, listOf(account.identity, extra))
        } finally { original.destroy() }
    }
    @Test fun explicitConfirmationExportsOnlyEmailRegardlessOfFilterAndPreservesIdentities() {
        val dir = Files.createTempDirectory("email-export-").toFile()
        val state = state(); val worker = Queue(); val ui = Queue()
        val entry = email(); entry.setDetailsVisible(false); state.add(entry)
        state.add(ManualEntryInput(secret = "JBSWY3DPEHPK3PXP").build())
        state.applySearch("no matching entries")
        val exporter = DesktopEmailExport(state, worker, ui::execute, {})
        try {
            val file = File(dir, "accounts.json")
            exporter.open(file); val request = exporter.pending!!
            assertEquals(DesktopEmailExport.Status.CONFIRM, request.status)
            assertTrue(worker.tasks.isEmpty()); assertFalse(file.exists())
            assertFalse(exporter.confirm(request) { false })
            assertTrue(exporter.confirm(request) { true })
            assertFalse(exporter.confirm(request) { true })
            entry.destroy() // The worker must use detached bytes, not the live account.
            worker.run(); ui.run()
            assertEquals(DesktopEmailExport.Status.SAVED, request.status)
            assertEquals(1, request.count)
            val bytes = file.readBytes()
            val imported = try { EmailAccounts.importEntries(bytes, null) } finally { bytes.fill(0) }
            try {
                val account = imported.single().getEmailAccount()
                assertEquals("incoming", account.account.getString("password"))
                assertEquals("preserved", account.account.getString("provider_extra"))
                assertEquals(2, account.identities.size)
                assertEquals("alias-secret", account.identities[1].getString("password"))
            } finally { imported.forEach { it.destroy() } }
        } finally { exporter.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun existingDestinationIsUntouchedAndCanceledQueuedWorkNeverWrites() {
        val dir = Files.createTempDirectory("email-export-existing-").toFile()
        val state = state(); state.add(email()); val worker = Queue(); val ui = Queue()
        val exporter = DesktopEmailExport(state, worker, ui::execute, {})
        try {
            val file = File(dir, "accounts.json").apply { writeText("keep") }
            exporter.open(file); val request = exporter.pending!!
            exporter.confirm(request) { true }; worker.run(); ui.run()
            assertEquals(DesktopEmailExport.Status.EXISTS, request.status)
            assertEquals("keep", file.readText()); exporter.dismiss(request)
            val canceled = File(dir, "canceled.json")
            exporter.open(canceled); val next = exporter.pending!!
            assertFalse(exporter.confirm(request) { true })
            exporter.confirm(next) { true }; exporter.close(); worker.run()
            assertFalse(canceled.exists()); assertTrue(ui.tasks.isEmpty())
        } finally { exporter.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun writesWipeBuffersAndLateCompletionCannotReviveDismissedRequest() {
        val state = state(); state.add(email()); val worker = Queue(); val ui = Queue()
        var captured: ByteArray? = null
        val exporter = DesktopEmailExport(state, worker, ui::execute, {}, write = { _, bytes -> captured = bytes })
        try {
            exporter.open(File("unused")); val request = exporter.pending!!
            exporter.confirm(request) { true }; worker.run()
            assertTrue(captured!!.all { it == 0.toByte() })
            exporter.dismiss(request); exporter.open(File("new-unused"))
            val next = exporter.pending!!; ui.run()
            assertTrue(exporter.pending === next)
            assertEquals(DesktopEmailExport.Status.CONFIRM, next.status)
        } finally { exporter.close(); state.lock() }
    }
    @Test fun closingDuringAnAuthorizedWriteOrRejectedDeliveryStillWipesTheBuffer() {
        val state = state(); state.add(email()); val worker = Queue(); val ui = Queue()
        var captured: ByteArray? = null
        lateinit var exporter: DesktopEmailExport
        exporter = DesktopEmailExport(state, worker, ui::execute, {}, write = { _, bytes ->
            captured = bytes; exporter.close()
        })
        try {
            exporter.open(File("unused")); exporter.confirm(exporter.pending!!) { true }
            worker.run(); ui.run()
            assertTrue(exporter.pending == null)
            assertTrue(captured!!.all { it == 0.toByte() })
            val rejected = DesktopEmailExport(state, worker, { throw RejectedExecutionException() }, {},
                write = { _, bytes -> captured = bytes })
            try {
                rejected.open(File("unused")); rejected.confirm(rejected.pending!!) { true }
                worker.run()
                assertTrue(captured!!.all { it == 0.toByte() })
            } finally { rejected.close() }
        } finally { exporter.close(); state.lock() }
    }
    @Test fun failedWriteWipesPlaintextAndReportsFailure() {
        val state = state(); state.add(email()); val worker = Queue(); val ui = Queue()
        var captured: ByteArray? = null
        val exporter = DesktopEmailExport(state, worker, ui::execute, {}, write = { _, bytes ->
            captured = bytes; error("write failed")
        })
        try {
            exporter.open(File("unused")); val request = exporter.pending!!
            exporter.confirm(request) { true }; worker.run(); ui.run()
            assertEquals(DesktopEmailExport.Status.FAILED, request.status)
            assertTrue(captured!!.all { it == 0.toByte() })
        } finally { exporter.close(); state.lock() }
    }
    @Test fun emptyLockedExpiredAndRejectedOperationsNeverWrite() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val exporter = DesktopEmailExport(state, worker, ui::execute, {}, write = { _, _ -> error("must not write") })
        try {
            exporter.open(File("unused")); val empty = exporter.pending!!
            assertFalse(exporter.confirm(empty) { true })
            assertEquals(DesktopEmailExport.Status.FAILED, empty.status)
            exporter.dismiss(empty); state.add(email())
            exporter.open(File("unused")); val expired = exporter.pending!!
            var checks = 0
            assertFalse(exporter.confirm(expired) { ++checks == 1 })
            assertTrue(worker.tasks.isEmpty())
            state.lock(); assertFalse(exporter.confirm(expired) { true })
        } finally { exporter.close(); state.lock() }
        val second = state(); second.add(email())
        val rejected = DesktopEmailExport(second, Executor { throw RejectedExecutionException() }, ui::execute, {})
        try {
            rejected.open(File("unused")); val request = rejected.pending!!
            rejected.confirm(request) { true }
            assertEquals(DesktopEmailExport.Status.FAILED, request.status)
        } finally { rejected.close(); second.lock() }
    }
    @Test fun lockBeforeWorkerAndExpiryBeforeDeliverySuppressCompletion() {
        val state = state(); state.add(email()); val worker = Queue(); val ui = Queue()
        var writes = 0; var active = true
        val exporter = DesktopEmailExport(state, worker, ui::execute, {}, write = { _, _ -> writes++ })
        try {
            exporter.open(File("unused")); val request = exporter.pending!!
            exporter.confirm(request) { active }; worker.run(); active = false; ui.run()
            assertEquals(1, writes)
            assertEquals(DesktopEmailExport.Status.WRITING, request.status)
            exporter.dismiss(request); exporter.open(File("unused"))
            exporter.confirm(exporter.pending!!) { true }; state.lock(); worker.run()
            assertEquals(1, writes)
        } finally { exporter.close(); state.lock() }
    }
}
