package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUnlockOperationTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runNext() = tasks.removeAt(0).run()
    }
    private class Key : SecretKey {
        var destroyed = false
        override fun getAlgorithm() = "AES"
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(32)
        override fun destroy() { destroyed = true }
    }
    private fun result(key: Key) = VaultController.Session.Loaded(
        arrayListOf(ManualEntryInput(label = "alice", secret = "JBSWY3DPEHPK3PXP").build()), key)

    @Test
    fun closeWhileARealWorkerIsDecryptingDiscardsItsLateResult() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val key = Key(); val loaded = result(key)
        val dispatched = java.util.concurrent.atomic.AtomicInteger(0)
        val worker = Executor { task -> executor.execute { try { task.run() } finally { finished.countDown() } } }
        val operation = DesktopUnlockOperation(worker, { dispatched.incrementAndGet() }, {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            loaded
        }, {}, { error("closed operation adopted a vault") })
        try {
            operation.submit("password")
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            operation.close(); release.countDown()
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(key.destroyed); assertTrue(loaded.entries.isEmpty())
            assertEquals(0, dispatched.get())
        } finally { release.countDown(); operation.close(); executor.shutdownNow() }
    }

    @Test
    fun closeDuringBusyCompletionDoesNotAdoptTheTakenResult() {
        val worker = Queue(); val ui = Queue(); val key = Key(); val loaded = result(key)
        var adopted = false
        lateinit var operation: DesktopUnlockOperation
        operation = DesktopUnlockOperation(worker, ui::execute, { loaded }, { busy ->
            if (!busy) operation.close()
        }, { adopted = true })
        operation.submit("password"); worker.runNext(); ui.runNext()
        assertFalse(adopted); assertTrue(key.destroyed); assertTrue(loaded.entries.isEmpty())
    }

    @Test
    fun cancellationBetweenWorkerOfferAndDispatcherEnqueueWipesImmediately() {
        val worker = Queue(); val ui = Queue(); val key = Key(); val loaded = result(key)
        lateinit var operation: DesktopUnlockOperation
        operation = DesktopUnlockOperation(worker, { callback ->
            operation.close()
            assertTrue(key.destroyed)
            assertTrue(loaded.entries.isEmpty())
            ui.execute(callback)
        }, { loaded }, {}, { error("closed") })
        operation.submit("password"); worker.runNext(); ui.runNext()
        assertTrue(key.destroyed)
    }

    @Test
    fun rejectedDispatchAfterEnqueueDropsResultEvenIfCallbackLaterRuns() {
        val worker = Queue(); val ui = Queue(); val key = Key(); val loaded = result(key)
        val operation = DesktopUnlockOperation(worker, { callback ->
            ui.execute(callback)
            throw RejectedExecutionException()
        }, { loaded }, {}, { error("discarded result adopted") })
        operation.submit("password"); worker.runNext()
        assertTrue(key.destroyed); assertTrue(loaded.entries.isEmpty())
        ui.runNext(); operation.close()
    }

    @Test
    fun backendRunsOnWorkerAndOnlyOneAttemptCanBePending() {
        val worker = Queue(); val ui = Queue(); val key = Key(); val loaded = result(key)
        var backendCalls = 0
        val busy = ArrayList<Boolean>()
        val adopted = ArrayList<VaultController.Session>()
        val operation = DesktopUnlockOperation(worker, ui::execute, { backendCalls++; loaded }, busy::add, adopted::add)
        assertTrue(operation.submit("password"))
        assertFalse(operation.submit("duplicate"))
        assertEquals(0, backendCalls)
        assertEquals(listOf(true), busy)
        worker.runNext()
        assertEquals(1, backendCalls)
        assertTrue(adopted.isEmpty())
        assertFalse(operation.submit("still pending delivery"))
        ui.runNext()
        assertEquals(listOf(true, false), busy)
        assertTrue(adopted.single() === loaded)
        operation.close()
        assertFalse(key.destroyed) // Adopted results belong to the session, not the operation.
        DesktopUnlockOperation.discard(loaded)
    }

    @Test
    fun closingBeforeWorkerStartsDropsPasswordWithoutCallingBackend() {
        val worker = Queue(); val ui = Queue()
        var backendCalls = 0
        val operation = DesktopUnlockOperation(worker, ui::execute, {
            backendCalls++; VaultController.Session.Error(VaultController.Failure.IO_FAILURE)
        }, {}, { error("closed screen received a result") })
        operation.submit("password")
        operation.close()
        worker.runNext()
        assertEquals(0, backendCalls)
        assertTrue(ui.tasks.isEmpty())
        assertFalse(operation.submit("late attempt"))
    }

    @Test
    fun closingWithAQueuedResultDestroysItsEntriesAndKey() {
        val worker = Queue(); val ui = Queue(); val key = Key(); val loaded = result(key)
        val entry = loaded.entries.single()
        val operation = DesktopUnlockOperation(worker, ui::execute, { loaded }, {}, { error("late result") })
        operation.submit("password")
        worker.runNext()
        operation.close()
        // Cleanup must not depend on a live UI queue: assert BEFORE delivery.
        assertEquals(0, entry.getSecretSize())
        assertTrue(loaded.entries.isEmpty())
        assertTrue(key.destroyed)
        ui.runNext() // Late delivery is harmless after immediate cleanup.
        assertTrue(loaded.entries.isEmpty())
    }

    @Test
    fun cancellationDuringBackendWorkDiscardsWithoutDispatch() {
        val worker = Queue(); val ui = Queue(); val key = Key(); val loaded = result(key)
        lateinit var operation: DesktopUnlockOperation
        operation = DesktopUnlockOperation(worker, ui::execute, { operation.close(); loaded }, {}, { error("late result") })
        operation.submit("password")
        worker.runNext()
        assertTrue(key.destroyed)
        assertTrue(ui.tasks.isEmpty())
    }

    @Test
    fun backendFailureCanRetryAndRejectedExecutorRestoresBusyState() {
        val worker = Queue(); val ui = Queue()
        val outcomes = ArrayList<VaultController.Session>()
        val busy = ArrayList<Boolean>()
        val operation = DesktopUnlockOperation(worker, ui::execute, { error("must not leak the password") }, busy::add, outcomes::add)
        operation.submit("password"); worker.runNext(); ui.runNext()
        assertEquals(VaultController.Session.Error(VaultController.Failure.IO_FAILURE), outcomes.single())
        assertTrue(operation.submit("retry"))
        operation.close(); worker.runNext()
        val rejected = DesktopUnlockOperation(Executor { throw RejectedExecutionException() }, ui::execute,
            { error("not called") }, busy::add, outcomes::add)
        rejected.submit("password")
        assertFalse(busy.last())
        assertEquals(2, outcomes.size)
        rejected.close()
    }

    @Test
    fun dispatchFailureDiscardsUnadoptedResult() {
        val worker = Queue(); val key = Key(); val loaded = result(key)
        val operation = DesktopUnlockOperation(worker, { throw RejectedExecutionException() }, { loaded }, {}, { error("not dispatched") })
        operation.submit("password"); worker.runNext()
        assertTrue(key.destroyed)
        assertTrue(loaded.entries.isEmpty())
        operation.close()
    }
}
