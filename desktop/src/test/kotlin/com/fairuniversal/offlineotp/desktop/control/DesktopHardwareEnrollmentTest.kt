package com.fairuniversal.offlineotp.desktop.control

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopHardwareEnrollmentTest {
    private class Queue : Executor {
        val tasks = arrayListOf<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 9 }, "AES"))
    @Test fun locksBeforeSchedulingUsesDetachedKeyAndDestroysItBeforeDelivery() {
        val state = state(); val source = state.key; val worker = Queue(); val ui = Queue(); var key: SecretKey? = null
        lateinit var owner: DesktopHardwareEnrollment
        owner = DesktopHardwareEnrollment(state, worker, ui::execute, {
            assertTrue(owner.busy); state.lock()
        }, { pin, copied, _ ->
            assertTrue(state.isLocked); assertTrue(copied !== source); key = copied
            assertTrue(copied.encoded.all { it == 9.toByte() }); assertEquals("1234", String(pin!!))
            HardwareKeyManager.Result.Ok(Unit)
        }, {})
        val pin = "1234".toCharArray()
        assertTrue(owner.submit(pin) { true }); assertTrue(state.isLocked); assertTrue(owner.busy)
        assertFalse(owner.submit(charArrayOf()) { true }); worker.run()
        assertTrue(key!!.isDestroyed); assertTrue(pin.all { it == ' ' }); assertTrue(owner.busy)
        ui.run(); assertEquals(DesktopHardwareEnrollment.Status.ENROLLED, owner.status); owner.close()
    }
    @Test fun noLockOrExpiredAuthorityNeverCallsProvider() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val owner = DesktopHardwareEnrollment(state, worker, ui::execute, {}, { _, _, _ -> error("unexpected") }, {})
        try {
            val pin = charArrayOf('x'); assertFalse(owner.submit(pin) { false }); assertTrue(pin.all { it == ' ' })
            assertFalse(owner.submit(charArrayOf('x')) { true }); assertTrue(worker.tasks.isEmpty())
            assertEquals(DesktopHardwareEnrollment.Status.FAILED, owner.status)
        } finally { owner.close(); state.lock() }
    }
    @Test fun queuedAndInFlightCancellationCleanKeyAndSuppressLateResult() {
        for (inFlight in listOf(false, true)) {
            val state = state(); val worker = Queue(); val ui = Queue(); var calls = 0; var key: SecretKey? = null
            lateinit var owner: DesktopHardwareEnrollment
            owner = DesktopHardwareEnrollment(state, worker, ui::execute, state::lock, { _, k, token ->
                key = k; calls++; owner.close(); assertTrue(token.isCanceled()); HardwareKeyManager.Result.Ok(Unit)
            }, {})
            val pin = charArrayOf('x'); owner.submit(pin) { true }
            if (!inFlight) owner.close()
            worker.run(); assertEquals(if (inFlight) 1 else 0, calls)
            assertTrue(pin.all { it == ' ' }); key?.let { assertTrue(it.isDestroyed) }
            if (inFlight) ui.run()
            assertEquals(DesktopHardwareEnrollment.Status.WORKING, owner.status)
        }
    }
    @Test fun failureAndExecutorRejectionLeaveLockedSessionAndCleanInputs() {
        for (reject in listOf(false, true)) {
            val state = state(); val worker = Queue(); val ui = Queue(); var key: SecretKey? = null
            val owner = DesktopHardwareEnrollment(state, if (reject) Executor { throw RejectedExecutionException() } else worker,
                ui::execute, state::lock, { _, k, _ -> key = k; error("private failure") }, {})
            val pin = charArrayOf('x'); owner.submit(pin) { true }
            if (!reject) { worker.run(); ui.run() }
            assertTrue(state.isLocked); assertTrue(pin.all { it == ' ' }); key?.let { assertTrue(it.isDestroyed) }
            assertEquals(DesktopHardwareEnrollment.Status.FAILED, owner.status); owner.close()
        }
    }
}
