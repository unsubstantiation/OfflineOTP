package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopHardwareCopyTest {
    private class Queue : Executor {
        val tasks = arrayListOf<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private fun entry() = ManualEntryInput(label = "alice", secret = "JBSWY3DPEHPK3PXP").build()
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")).also { it.add(entry()) }
    @Test fun explicitConsentExportsDetachedEntryAndCleansWorkerMaterial() {
        val state = state(); val worker = Queue(); val ui = Queue(); var calls = 0; var detached: Entry? = null
        val source = state.snapshot().single()
        state.add(ManualEntryInput(type = Entry.OTPType.NOTE, label = "note", secret = "private").build())
        state.applySearch("no matches")
        val owner = DesktopHardwareCopy(state, worker, ui::execute, {}, { pin, entry, _ ->
            calls++; detached = entry
            assertTrue(entry !== source); assertEquals("alice", entry.getLabel()); assertEquals("password", String(pin!!))
            HardwareKeyManager.Result.Ok(Unit)
        })
        try {
            owner.open(); val request = owner.pending!!; assertEquals(1, request.labels.size)
            val early = charArrayOf('x'); assertFalse(owner.submit(request, early) { true }); assertTrue(early.all { it == ' ' })
            assertTrue(owner.select(request, 0) { true }); assertEquals(0, calls)
            val pin = "password".toCharArray(); assertTrue(owner.submit(request, pin) { true })
            assertFalse(owner.submit(request, charArrayOf()) { true })
            source.setLabel("changed after snapshot"); worker.run()
            assertEquals(0, detached!!.getSecretSize()); assertTrue(pin.all { it == ' ' })
            assertTrue(source.getSecretSize() > 0); ui.run(); assertEquals(DesktopHardwareCopy.Status.COPIED, request.status)
        } finally { owner.close(); state.lock() }
    }
    @Test fun replacedDismissedAndExpiredRequestsCannotExportSeeds() {
        val state = state(); val worker = Queue(); val ui = Queue(); var calls = 0
        val owner = DesktopHardwareCopy(state, worker, ui::execute, {}, { _, _, _ -> calls++; HardwareKeyManager.Result.Ok(Unit) })
        try {
            owner.open(); val old = owner.pending!!; owner.dismiss(old); owner.open(); val next = owner.pending!!
            assertFalse(owner.select(old, 0) { true }); assertFalse(owner.select(next, 0) { false })
            owner.select(next, 0) { true }
            state.replace(state.snapshot().single(), entry())
            val pin = charArrayOf('x'); assertFalse(owner.submit(next, pin) { true })
            assertTrue(pin.all { it == ' ' }); assertTrue(owner.pending == null); assertEquals(0, calls)
        } finally { owner.close(); state.lock() }
    }
    @Test fun queuedCancellationAndLockDoNotCallProvider() {
        for (lock in listOf(false, true)) {
            val state = state(); val worker = Queue(); val ui = Queue(); var calls = 0
            val owner = DesktopHardwareCopy(state, worker, ui::execute, {}, { _, _, _ -> calls++; HardwareKeyManager.Result.Ok(Unit) })
            try {
                owner.open(); val request = owner.pending!!; owner.select(request, 0) { true }
                val pin = charArrayOf('x'); owner.submit(request, pin) { true }
                if (lock) state.lock() else owner.close()
                worker.run(); assertEquals(0, calls); assertTrue(pin.all { it == ' ' })
            } finally { owner.close(); state.lock() }
        }
    }
    @Test fun inFlightCancellationSignalsTokenAndDropsLateCompletion() {
        val state = state(); val worker = Queue(); val ui = Queue(); var copied: Entry? = null
        lateinit var owner: DesktopHardwareCopy
        owner = DesktopHardwareCopy(state, worker, ui::execute, {}, { _, entry, token ->
            copied = entry; owner.close(); assertTrue(token.isCanceled()); HardwareKeyManager.Result.Ok(Unit)
        })
        try {
            owner.open(); val request = owner.pending!!; owner.select(request, 0) { true }
            owner.submit(request, charArrayOf('x')) { true }; worker.run(); ui.run()
            assertTrue(owner.pending == null); assertEquals(0, copied!!.getSecretSize())
            assertEquals(DesktopHardwareCopy.Status.WRITING, request.status)
        } finally { owner.close(); state.lock() }
    }
    @Test fun exceptionsAndRejectedExecutionWipePinsWithoutLeakingMessages() {
        for (reject in listOf(false, true)) {
            val state = state(); val worker = Queue(); val ui = Queue(); var copied: Entry? = null
            val owner = DesktopHardwareCopy(state, if (reject) Executor { throw RejectedExecutionException() } else worker,
                ui::execute, {}, { _, entry, _ -> copied = entry; error("private provider details") })
            try {
                owner.open(); val request = owner.pending!!; owner.select(request, 0) { true }
                val pin = charArrayOf('x'); owner.submit(request, pin) { true }
                if (!reject) { worker.run(); ui.run(); assertEquals(0, copied!!.getSecretSize()) }
                assertTrue(pin.all { it == ' ' }); assertEquals(DesktopHardwareCopy.Status.FAILED, request.status)
            } finally { owner.close(); state.lock() }
        }
    }
}
