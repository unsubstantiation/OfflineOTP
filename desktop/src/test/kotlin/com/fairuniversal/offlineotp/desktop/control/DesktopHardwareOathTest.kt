package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.HardwareOathClient
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopHardwareOathTest {
    private class Queue : Executor {
        val tasks = arrayListOf<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
    @Test fun operationsUseWorkerWipePinAndRequireCurrentRequest() {
        val state = state(); val worker = Queue(); val ui = Queue(); var calls = 0
        val owner = DesktopHardwareOath(state, worker, ui::execute, {}, { pin, _ ->
            calls++; assertEquals("password", String(pin!!)); HardwareKeyManager.Result.Ok(HardwareOathClient.Accounts("device", emptyList()))
        }, { _, _, _, _ -> error("unexpected calculation") })
        try {
            owner.open(); val request = owner.pending!!; val pin = "password".toCharArray()
            assertTrue(owner.submit(request, pin, null) { true }); assertEquals(0, calls)
            val duplicate = "duplicate".toCharArray()
            assertFalse(owner.submit(request, duplicate, null) { true }); assertTrue(duplicate.all { it == ' ' })
            worker.run(); assertTrue(pin.all { it == ' ' }); assertTrue(request.busy)
            ui.run(); assertFalse(request.busy); assertEquals("No OATH accounts found.", request.message)
            val invalid = "invalid".toCharArray()
            assertFalse(owner.submit(request, invalid, 0) { true }); assertTrue(invalid.all { it == ' ' })
            owner.dismiss(request); owner.open()
            assertFalse(owner.submit(request, charArrayOf('x'), null) { true })
        } finally { owner.close(); state.lock() }
    }
    @Test fun cancellationBeforeWorkerAndDuringBackendDropsLateResult() {
        val state = state(); val worker = Queue(); val ui = Queue(); var calls = 0
        lateinit var owner: DesktopHardwareOath
        owner = DesktopHardwareOath(state, worker, ui::execute, {}, { _, token ->
            calls++; owner.close(); assertTrue(token.isCanceled())
            HardwareKeyManager.Result.Ok(HardwareOathClient.Accounts("device", emptyList()))
        }, { _, _, _, _ -> error("unexpected") })
        owner.open(); val first = owner.pending!!; val pin = charArrayOf('x')
        owner.submit(first, pin, null) { true }; owner.dismiss(first); worker.run()
        assertEquals(0, calls); assertTrue(pin.all { it == ' ' })
        owner.open(); owner.submit(owner.pending!!, charArrayOf('x'), null) { true }; worker.run()
        assertEquals(1, calls); assertTrue(owner.pending == null); assertTrue(ui.tasks.isEmpty()); state.lock()
    }
    @Test fun rejectedAndFailedOperationsDoNotLeakPinOrProviderMessages() {
        for (reject in listOf(false, true)) {
            val state = state(); val worker = Queue(); val ui = Queue()
            val owner = DesktopHardwareOath(state, if (reject) Executor { throw RejectedExecutionException() } else worker,
                ui::execute, {}, { _, _ -> error("secret provider message") }, { _, _, _, _ -> error("unexpected") })
            try {
                owner.open(); val request = owner.pending!!; val pin = charArrayOf('x')
                owner.submit(request, pin, null) { true }
                if (!reject) { worker.run(); ui.run() }
                assertTrue(pin.all { it == ' ' }); assertFalse(request.busy)
                assertFalse(request.message.orEmpty().contains("secret provider"))
            } finally { owner.close(); state.lock() }
        }
    }
    @Test fun staleDeliveryAndExpiredSubmissionCannotPublish() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val owner = DesktopHardwareOath(state, worker, ui::execute, {}, { _, _ -> HardwareKeyManager.Result.NoKey },
            { _, _, _, _ -> error("unexpected") })
        try {
            owner.open(); val old = owner.pending!!; val pin = charArrayOf('x')
            assertFalse(owner.submit(old, pin, null) { false }); assertTrue(pin.all { it == ' ' })
            owner.submit(old, charArrayOf(), null) { true }; worker.run()
            owner.dismiss(old); owner.open(); val next = owner.pending!!; ui.run()
            assertTrue(owner.pending === next); assertTrue(next.message == null)
            state.lock(); assertTrue(owner.rows(next, 0).isEmpty())
            assertFalse(owner.submit(next, charArrayOf(), null) { true })
        } finally { owner.close(); state.lock() }
    }
}
