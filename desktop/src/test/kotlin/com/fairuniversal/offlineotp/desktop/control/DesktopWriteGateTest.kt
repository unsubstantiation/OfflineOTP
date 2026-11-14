package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWriteGateTest {
    @Test fun abandonmentCannotReleaseAStartedWriteEarlyAndOldLeaseCannotReleaseNewOwner() {
        val gate = DesktopWriteGate(); val first = gate.acquire()!!
        assertTrue(first.start()); first.acknowledged(); assertTrue(gate.busy)
        first.abandon(); assertTrue(gate.busy); assertTrue(gate.acquire() == null)
        first.workerFinished(); assertFalse(gate.busy)
        val second = gate.acquire()!!
        first.abandon(); first.workerFinished(); first.acknowledged(); assertTrue(gate.busy)
        second.abandon(); assertFalse(gate.busy); assertFalse(second.start())
    }
    @Test fun normalCompletionWaitsForAcknowledgement() {
        val gate = DesktopWriteGate(); val lease = gate.acquire()!!
        assertTrue(lease.start()); assertFalse(lease.start()); lease.workerFinished()
        assertTrue(gate.busy); lease.acknowledged(); assertFalse(gate.busy)
    }
    @Test fun independentSaveOwnersShareWriteAuthorityThroughUiAdoption() {
        val gate = DesktopWriteGate(); val queue = ArrayList<Runnable>(); val ui = ArrayList<Runnable>()
        val worker = Executor { queue.add(it) }
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        state.add(ManualEntryInput(label = "test", secret = "JBSWY3DPEHPK3PXP").build())
        val first = DesktopDetachedSave(worker, { ui.add(it) }, gate) { _, _ -> true }
        val second = DesktopDetachedSave(worker, { ui.add(it) }, gate) { _, _ -> true }
        try {
            assertTrue(first.submit(state, { true }) {
                assertTrue(gate.busy)
                assertFalse(second.submit(state, { true }) {})
            })
            assertFalse(second.submit(state, { true }) {})
            queue.removeAt(0).run(); assertTrue(gate.busy)
            assertFalse(second.submit(state, { true }) {})
            ui.removeAt(0).run(); assertFalse(gate.busy)
            assertTrue(second.submit(state, { true }) {})
            second.close(); assertFalse(gate.busy)
            queue.removeAt(0).run()
        } finally { first.close(); second.close(); state.lock() }
    }
    @Test fun closeInsidePersistenceKeepsGateUntilWorkerReturns() {
        val gate = DesktopWriteGate(); val queue = ArrayList<Runnable>(); val ui = ArrayList<Runnable>()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        lateinit var owner: DesktopDetachedSave
        owner = DesktopDetachedSave(Executor { queue.add(it) }, { ui.add(it) }, gate) { _, _ ->
            owner.close(); assertTrue(gate.busy); assertTrue(gate.acquire() == null); true
        }
        try {
            assertTrue(owner.submit(state, { true }) { error("late") })
            queue.removeAt(0).run(); assertFalse(gate.busy)
            ui.removeAt(0).run(); assertFalse(gate.busy)
        } finally { owner.close(); state.lock() }
    }
    @Test fun rejectedSchedulingAndExpiredSnapshotReleaseUnstartedLease() {
        val gate = DesktopWriteGate()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val owner = DesktopDetachedSave(Executor { error("rejected") }, { it.run() }, gate) { _, _ -> true }
        try {
            assertFalse(owner.submit(state, { true }) {}); assertFalse(gate.busy)
            var checks = 0
            assertFalse(owner.submit(state, { ++checks == 1 }) {}); assertFalse(gate.busy)
        } finally { owner.close(); state.lock() }
    }

    @Test fun rejectedDeliveryAndFailedAdoptionRetainLease() {
        for (rejectDispatch in listOf(true, false)) {
            val gate = DesktopWriteGate(); val queue = ArrayList<Runnable>(); val ui = ArrayList<Runnable>()
            val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
            val owner = DesktopDetachedSave(Executor { queue.add(it) }, { if (rejectDispatch) error("dispatch") else ui.add(it) }, gate) { _, _ -> true }
            try {
                owner.submit(state, { true }) { error("adoption") }
                queue.removeAt(0).run()
                if (!rejectDispatch) try { ui.removeAt(0).run() } catch (_: IllegalStateException) {}
                assertTrue(gate.busy); assertTrue(gate.acquire() == null)
            } finally { owner.close(); state.lock() }
        }
    }
}
