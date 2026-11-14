package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopHardwareUnlockTest {
    private class Queue : Executor {
        val tasks = arrayListOf<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private class Key : SecretKey {
        var destroyed = false
        override fun getAlgorithm() = "AES"
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(32)
        override fun destroy() { destroyed = true }
    }
    private fun loaded(key: SecretKey) = VaultController.Session.Loaded(arrayListOf(ManualEntryInput(secret = "JBSWY3DPEHPK3PXP").build()), key)
    @Test fun successTransfersKeyOnceAndWipesPinBeforeUiDelivery() {
        val worker = Queue(); val ui = Queue(); val key = Key(); val result = loaded(key); var adopted = false
        val owner = DesktopHardwareUnlock(worker, ui::execute, { pin, _ ->
            assertEquals("1234", String(pin!!)); HardwareKeyManager.Result.Ok(key)
        }, { assertTrue(it === key); result }, {}, { adopted = it === result })
        val pin = "1234".toCharArray()
        owner.submit(pin); assertFalse(owner.submit(charArrayOf('x')))
        worker.run(); assertTrue(pin.all { it == ' ' }); assertFalse(adopted); assertFalse(key.destroyed)
        ui.run(); assertTrue(adopted); owner.close(); assertFalse(key.destroyed)
        DesktopUnlockOperation.discard(result)
    }
    @Test fun queuedResultIsDestroyedImmediatelyOnClose() {
        val worker = Queue(); val ui = Queue(); val key = Key(); val result = loaded(key)
        val owner = DesktopHardwareUnlock(worker, ui::execute, { _, _ -> HardwareKeyManager.Result.Ok(key) }, { result }, {}, { error("late") })
        owner.submit(charArrayOf()); worker.run(); owner.close()
        assertTrue(key.destroyed); assertTrue(result.entries.isEmpty()); ui.run()
    }
    @Test fun cancellationDuringRecoveryNeverOpensVaultAndDestroysRecoveredKey() {
        val worker = Queue(); val ui = Queue(); val key = Key(); var opens = 0
        lateinit var owner: DesktopHardwareUnlock
        owner = DesktopHardwareUnlock(worker, ui::execute, { _, token ->
            owner.close(); assertTrue(token.isCanceled()); HardwareKeyManager.Result.Ok(key)
        }, { opens++; loaded(it) }, {}, { error("late") })
        val pin = charArrayOf('x'); owner.submit(pin); worker.run()
        assertTrue(key.destroyed); assertEquals(0, opens); assertTrue(pin.all { it == ' ' })
    }
    @Test fun failedOpenAndDifferentSessionKeyReleaseRecoveredKey() {
        for (success in listOf(false, true)) {
            val worker = Queue(); val ui = Queue(); val recovered = Key(); val adopted = Key()
            var result: VaultController.Session? = null
            val owner = DesktopHardwareUnlock(worker, ui::execute, { _, _ -> HardwareKeyManager.Result.Ok(recovered) }, {
                if (success) loaded(adopted) else error("private failure")
            }, {}, { result = it })
            owner.submit(charArrayOf()); worker.run(); assertTrue(recovered.destroyed); ui.run()
            assertTrue(if (success) result is VaultController.Session.Loaded else result is VaultController.Session.Error)
            owner.close(); result?.let(DesktopUnlockOperation::discard)
        }
    }
    @Test fun queuedCancellationAndRejectionWipePin() {
        val worker = Queue(); val ui = Queue(); var calls = 0
        val owner = DesktopHardwareUnlock(worker, ui::execute, { _, _ -> calls++; HardwareKeyManager.Result.NoKey }, { error("unexpected") }, {}, {})
        val pin = charArrayOf('x'); owner.submit(pin); owner.close(); worker.run()
        assertEquals(0, calls); assertTrue(pin.all { it == ' ' })
        val rejected = DesktopHardwareUnlock(Executor { throw RejectedExecutionException() }, ui::execute,
            { _, _ -> error("unexpected") }, { error("unexpected") }, {}, {})
        val second = charArrayOf('x'); rejected.submit(second); assertTrue(second.all { it == ' ' }); rejected.close()
    }
}
