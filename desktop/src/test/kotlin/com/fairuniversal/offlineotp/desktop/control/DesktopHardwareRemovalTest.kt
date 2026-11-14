package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.desktop.storage.DesktopHardwareUnlockStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopHardwareRemovalTest {
    private class Queue : Executor {
        val tasks = arrayListOf<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
    @Test fun explicitConfirmationDeletesEvenCorruptRecordAndMissingRecordIsIdempotent() {
        val dir = Files.createTempDirectory("hardware-removal-").toFile()
        val file = File(dir, DesktopHardwareUnlockStore.RECORD_FILE).apply { writeText("corrupt") }
        val store = DesktopHardwareUnlockStore(dir); val state = state(); val worker = Queue(); val ui = Queue()
        val owner = DesktopHardwareRemoval(state, worker, ui::execute, store::removeEnvelope, {})
        try {
            owner.open(); val request = owner.pending!!
            assertTrue(file.exists()); assertTrue(worker.tasks.isEmpty())
            assertFalse(owner.confirm(request) { false })
            assertTrue(owner.confirm(request) { true }); assertFalse(owner.confirm(request) { true })
            assertTrue(file.exists()); worker.run(); assertFalse(file.exists()); ui.run()
            assertEquals(DesktopHardwareRemoval.Status.REMOVED, request.status); assertFalse(state.isLocked)
            store.removeEnvelope() // Already absent succeeds.
        } finally { owner.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun failedFilesystemDeletionIsReportedNotSilentlyIgnored() {
        val dir = Files.createTempDirectory("hardware-remove-failure-").toFile()
        val record = File(dir, DesktopHardwareUnlockStore.RECORD_FILE).apply { mkdir() }
        File(record, "keep").writeText("keep")
        val state = state(); val worker = Queue(); val ui = Queue()
        val owner = DesktopHardwareRemoval(state, worker, ui::execute, DesktopHardwareUnlockStore(dir)::removeEnvelope, {})
        try {
            owner.open(); val request = owner.pending!!; owner.confirm(request) { true }; worker.run(); ui.run()
            assertEquals(DesktopHardwareRemoval.Status.FAILED, request.status); assertTrue(record.exists())
        } finally { owner.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun canceledStaleLockedAndExpiredRequestsDoNotDelete() {
        val state = state(); val worker = Queue(); val ui = Queue(); var removals = 0
        val owner = DesktopHardwareRemoval(state, worker, ui::execute, { removals++ }, {})
        try {
            owner.open(); val old = owner.pending!!; owner.dismiss(old); owner.open(); val request = owner.pending!!
            assertFalse(owner.confirm(old) { true })
            assertFalse(owner.confirm(request) { false })
            owner.confirm(request) { true }; owner.dismiss(request); worker.run(); assertEquals(0, removals)
            owner.open(); owner.confirm(owner.pending!!) { true }; state.lock(); worker.run(); assertEquals(0, removals)
        } finally { owner.close(); state.lock() }
    }
    @Test fun lateCompletionDoesNotClearNewRequestAndRejectionReportsFailure() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val owner = DesktopHardwareRemoval(state, worker, ui::execute, {}, {})
        try {
            owner.open(); val old = owner.pending!!; owner.confirm(old) { true }; worker.run()
            owner.dismiss(old); owner.open(); val current = owner.pending!!; ui.run()
            assertTrue(owner.pending === current); assertEquals(DesktopHardwareRemoval.Status.CONFIRM, current.status)
            val rejected = DesktopHardwareRemoval(state, Executor { throw RejectedExecutionException() }, ui::execute, { error("not called") }, {})
            rejected.open(); rejected.confirm(rejected.pending!!) { true }
            assertEquals(DesktopHardwareRemoval.Status.FAILED, rejected.pending!!.status); rejected.close()
        } finally { owner.close(); state.lock() }
    }
}
