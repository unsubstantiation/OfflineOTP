package com.fairuniversal.offlineotp.desktop.control

import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPasswordChangeFlowTest {
    private class Queue : Executor {
        val tasks = arrayListOf<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
    @Test fun unlockDismissalAndWindowExitStayBlockedThroughBackendAndQueuedDelivery() {
        val state = state(); val worker = Queue(); val ui = Queue(); var calls = 0
        val flow = DesktopPasswordChangeFlow(worker, ui::execute, { _, _ ->
            calls++; VaultController.Session.Error(VaultController.Failure.IO_FAILURE)
        }, {})
        try {
            assertTrue(flow.canUnlock)
            flow.open(state, { true }) { state.lock(); flow.sessionLocked() }
            val owner = flow.operation!!
            assertFalse(flow.canUnlock); assertTrue(flow.canCloseWindow)
            assertTrue(flow.submit(owner, "old", "new", "new"))
            assertTrue(state.isLocked); assertTrue(flow.operation === owner)
            assertFalse(flow.canCloseWindow); flow.dismiss(owner); assertTrue(flow.operation === owner)
            assertFalse(flow.open(state, { true }, {}))
            assertFalse(flow.submit(owner, "old", "other", "other"))
            worker.run(); assertEquals(1, calls)
            assertFalse(flow.canUnlock); assertFalse(flow.canCloseWindow)
            ui.run(); assertTrue(flow.canCloseWindow); assertFalse(flow.canUnlock)
            flow.dismiss(owner); assertTrue(flow.canUnlock)
        } finally { flow.close(); state.lock() }
    }
    @Test fun idleLockCancelsUnsubmittedFormAndStaleFormCannotSubmit() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val flow = DesktopPasswordChangeFlow(worker, ui::execute, { _, _ -> error("unexpected") }, {})
        try {
            flow.open(state, { true }, state::lock); val owner = flow.operation!!
            assertFalse(flow.submit(owner, "old", "new", "mismatch"))
            state.lock(); flow.sessionLocked()
            assertTrue(flow.operation == null); assertTrue(flow.canUnlock)
            assertFalse(flow.submit(owner, "old", "new", "new")); assertTrue(worker.tasks.isEmpty())
        } finally { flow.close(); state.lock() }
    }
    @Test fun rejectedDeliveryFailsClosedAndQueuedCancellationCannotRunBackend() {
        val state = state(); val worker = Queue()
        val flow = DesktopPasswordChangeFlow(worker, { error("dispatcher rejected") }, { _, _ ->
            VaultController.Session.Error(VaultController.Failure.IO_FAILURE)
        }, {})
        flow.open(state, { true }, state::lock); flow.submit(flow.operation!!, "old", "new", "new")
        worker.run(); assertFalse(flow.canUnlock); assertFalse(flow.canCloseWindow); flow.close()
        assertFalse(flow.canUnlock)
        val second = state(); var calls = 0
        val canceled = DesktopPasswordChangeFlow(worker, {}, { _, _ -> calls++; error("unexpected") }, {})
        canceled.open(second, { true }, second::lock); canceled.submit(canceled.operation!!, "old", "new", "new")
        canceled.close(); worker.run(); assertEquals(0, calls)
    }
}
