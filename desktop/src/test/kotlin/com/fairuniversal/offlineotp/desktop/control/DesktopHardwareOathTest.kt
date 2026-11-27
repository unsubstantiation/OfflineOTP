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
    @Test fun deletionUsesSelectedIdentityClearsListAndNeverRetriesFailure() {
        for (succeeds in listOf(true, false)) {
            val state = state(); val worker = Queue(); val ui = Queue(); var deletes = 0
            val account = HardwareOathClient.Account(com.yubico.yubikit.oath.Credential(byteArrayOf(1)), null, 0)
            val owner = DesktopHardwareOath(state, worker, ui::execute, {}, { _, _ ->
                HardwareKeyManager.Result.Ok(HardwareOathClient.Accounts("selected-device", listOf(account)))
            }, { _, _, _, _ -> error("Deletion must not calculate") }, { pin, device, selected, _ ->
                assertEquals("again", String(pin!!)); assertEquals("selected-device", device); assertTrue(selected === account)
                deletes++
                if (succeeds) HardwareKeyManager.Result.Ok(Unit) else HardwareKeyManager.Result.Failed
            })
            try {
                owner.open(); val request = owner.pending!!
                owner.submit(request, charArrayOf(), null) { true }; worker.run(); ui.run()
                val pin = "again".toCharArray()
                assertTrue(owner.submit(request, pin, 0, true) { true })
                worker.run(); ui.run()
                assertTrue(pin.all { it == ' ' }); assertEquals(1, deletes)
                assertTrue(owner.rows(request, 0).isEmpty()); assertFalse(request.busy)
                assertTrue(request.message!!.contains(if (succeeds) "Vault unchanged" else "may already have completed"))
                assertFalse(owner.submit(request, charArrayOf(), 0, true) { true }); assertEquals(1, deletes)
            } finally { owner.close(); state.lock() }
        }
    }

    @Test fun inactiveDeliveryRetiresStateAndAllowsFreshRead() {
        val state = state(); val worker = Queue(); val ui = Queue(); var active = true
        val owner = DesktopHardwareOath(state, worker, ui::execute, {}, { _, _ ->
            HardwareKeyManager.Result.Ok(HardwareOathClient.Accounts("device", emptyList()))
        }, { _, _, _, _ -> error("unexpected") })
        try {
            owner.open(); val request = owner.pending!!
            assertTrue(owner.submit(request, charArrayOf(), null) { active })
            worker.run(); active = false; ui.run()
            assertFalse(request.busy); assertTrue(owner.rows(request, 0).isEmpty())
            assertTrue(request.message!!.contains("inactive"))
            active = true
            assertTrue(owner.submit(request, charArrayOf(), null) { active })
            worker.run(); ui.run(); assertFalse(request.busy)
            assertEquals("No OATH accounts found.", request.message)
        } finally { owner.close(); state.lock() }
    }

    @Test fun keyLabelsCannotSpoofDestructiveConfirmationWithDirectionalControls() {
        val state = state(); val worker = Queue(); val ui = Queue()
        val credential = com.yubico.yubikit.oath.Credential(byteArrayOf(1), issuer = "issuer\u202e", accountName = "account\u0000\u2028")
        val owner = DesktopHardwareOath(state, worker, ui::execute, {}, { _, _ ->
            HardwareKeyManager.Result.Ok(HardwareOathClient.Accounts("device", listOf(HardwareOathClient.Account(credential, null, 0))))
        }, { _, _, _, _ -> error("unexpected") })
        try {
            owner.open(); val request = owner.pending!!
            owner.submit(request, charArrayOf(), null) { true }; worker.run(); ui.run()
            val label = owner.rows(request, 0).single().label
            assertTrue(label.contains("issuer")); assertTrue(label.contains("account"))
            assertFalse(label.any { it.isISOControl() || it == '\u2028' || Character.getType(it) == Character.FORMAT.toInt() })
        } finally { owner.close(); state.lock() }
    }

    @Test fun renameClearsSelectionNeverCalculatesAndDoesNotRetryUncertainOutcomes() {
        for (outcome in listOf(HardwareKeyManager.Result.Ok(Unit), HardwareKeyManager.Result.Failed, HardwareKeyManager.Result.Canceled)) {
            val state = state(); val worker = Queue(); val ui = Queue(); var renames = 0
            val account = HardwareOathClient.Account(com.yubico.yubikit.oath.Credential("old".toByteArray()), null, 0)
            val owner = DesktopHardwareOath(state, worker, ui::execute, {}, { _, _ ->
                HardwareKeyManager.Result.Ok(HardwareOathClient.Accounts("chosen-key", listOf(account)))
            }, { _, _, _, _ -> error("Rename must not calculate") }, rename = { pin, device, selected, issuer, name, _ ->
                assertEquals("fresh", String(pin!!)); assertEquals("chosen-key", device); assertTrue(selected === account)
                assertEquals("issuer", issuer); assertEquals("new", name); renames++; outcome
            })
            try {
                owner.open(); val request = owner.pending!!
                owner.submit(request, charArrayOf(), null) { true }; worker.run(); ui.run()
                val invalid = charArrayOf('x')
                assertFalse(owner.rename(request, invalid, 0, "bad:issuer", "new") { true })
                assertTrue(invalid.all { it == ' ' }); assertEquals(0, renames)
                val pin = "fresh".toCharArray()
                assertTrue(owner.rename(request, pin, 0, "issuer", "new") { true })
                assertEquals(0, renames); assertTrue(request.busy)
                worker.run(); ui.run()
                assertTrue(pin.all { it == ' ' }); assertEquals(1, renames); assertFalse(request.busy)
                assertTrue(owner.rows(request, 0).isEmpty()); assertTrue(request.message!!.contains("Vault unchanged"))
                assertFalse(owner.rename(request, charArrayOf(), 0, "issuer", "new") { true }); assertEquals(1, renames)
            } finally { owner.close(); state.lock() }
        }
    }

    @Test fun queuedRenameIsRevocableAndLateResultsCannotReplaceANewRequest() {
        val state = state(); val worker = Queue(); val ui = Queue(); var calls = 0
        val account = HardwareOathClient.Account(com.yubico.yubikit.oath.Credential("old".toByteArray()), null, 0)
        val owner = DesktopHardwareOath(state, worker, ui::execute, {}, { _, _ ->
            HardwareKeyManager.Result.Ok(HardwareOathClient.Accounts("key", listOf(account)))
        }, { _, _, _, _ -> error("unexpected") }, rename = { _, _, _, _, _, _ -> calls++; HardwareKeyManager.Result.Ok(Unit) })
        fun listed(): DesktopHardwareOath.Request {
            owner.open(); val request = owner.pending!!
            owner.submit(request, charArrayOf(), null) { true }; worker.run(); ui.run(); return request
        }
        try {
            val first = listed(); val pin = charArrayOf('x')
            owner.rename(first, pin, 0, "", "new") { true }; owner.dismiss(first); worker.run()
            assertEquals(0, calls); assertTrue(pin.all { it == ' ' })
            val second = listed()
            owner.rename(second, charArrayOf(), 0, "", "new") { true }; worker.run()
            owner.dismiss(second); owner.open(); val next = owner.pending!!; ui.run()
            assertTrue(owner.pending === next); assertTrue(next.message == null); assertEquals(1, calls)
        } finally { owner.close(); state.lock() }
    }

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
