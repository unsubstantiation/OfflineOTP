package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEntryTransactionDeadlineTest {
    private class Clock : ScreenTimerController.Scheduler {
        var now = 0L
        override fun elapsedRealtime() = now
        override fun postDelayed(runnable: Runnable, delayMillis: Long) {}
        override fun remove(runnable: Runnable) {}
    }
    private fun entry(kind: DesktopEntryTransaction.Kind) = if (kind == DesktopEntryTransaction.Kind.SET_PIN)
        ManualEntryInput(type = Entry.OTPType.MOTP, label = "motp", secret = "0123456789abcdef", pin = "1234").build()
    else ManualEntryInput(type = Entry.OTPType.HOTP, label = "hotp", secret = "JBSWY3DPEHPK3PXP", timing = "0").build()

    @Test fun committedWritesCrossingIdleDeadlineLockWithoutDeliveringResult() {
        for (kind in DesktopEntryTransaction.Kind.values().filter { it !in listOf(DesktopEntryTransaction.Kind.ADD, DesktopEntryTransaction.Kind.REPLACE) }) {
            val dir = Files.createTempDirectory("transaction-deadline-").toFile()
            val controller = VaultController(DesktopStorage.open(dir))
            val loaded = controller.createVault("password") as VaultController.Session.Loaded
            val state = DesktopVaultState(loaded.key); val target = entry(kind); state.add(target)
            val worker = ArrayList<Runnable>(); val ui = ArrayList<Runnable>(); val clock = Clock()
            val owner = DesktopEntryTransaction(state, controller.detachedSave(Executor { worker.add(it) }, { ui.add(it) }), state::lock)
            val lifetime = DesktopSessionLifetime(clock, 1) { owner.close(); state.lock() }
            val pin = "9876".toCharArray()
            try {
                val accepted = if (kind == DesktopEntryTransaction.Kind.SET_PIN)
                    owner.submitPin(target, pin, lifetime::checkActive) { error("expired result") }
                else owner.submit(target, kind, lifetime::checkActive) { error("expired result") }
                assertTrue(accepted); worker.removeAt(0).run()
                clock.now = 1000; ui.removeAt(0).run()
                assertTrue(state.isLocked); assertFalse(controller.persistenceBusy)
                if (kind == DesktopEntryTransaction.Kind.SET_PIN) assertTrue(pin.all { it == '\u0000' })
                val reopened = controller.openVault("password") as VaultController.Session.Loaded
                try {
                    when (kind) {
                        DesktopEntryTransaction.Kind.ADD, DesktopEntryTransaction.Kind.REPLACE -> error("Creation has separate transaction coverage")
                        DesktopEntryTransaction.Kind.DELETE -> assertTrue(reopened.entries.isEmpty())
                        DesktopEntryTransaction.Kind.ADVANCE_COUNTER -> assertEquals(1L, reopened.entries.single().getCounter())
                        DesktopEntryTransaction.Kind.SET_PIN -> assertEquals("9876", reopened.entries.single().getPin())
                    }
                } finally { DesktopUnlockOperation.discard(reopened) }
            } finally { lifetime.close(); owner.close(); state.lock(); pin.fill('\u0000'); dir.deleteRecursively() }
        }
    }
    @Test fun failedAndThrowingWritesStillCheckRealLifetimeBeforeDelivering() {
        for (kind in DesktopEntryTransaction.Kind.values().filter { it !in listOf(DesktopEntryTransaction.Kind.ADD, DesktopEntryTransaction.Kind.REPLACE) }) for (throws in listOf(false, true)) {
            val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val target = entry(kind); state.add(target)
            val worker = ArrayList<Runnable>(); val ui = ArrayList<Runnable>(); val clock = Clock(); val gate = DesktopWriteGate()
            val save = DesktopDetachedSave(Executor { worker.add(it) }, { ui.add(it) }, gate) { _, _ ->
                clock.now = 1000; if (throws) error("storage") else false
            }
            val owner = DesktopEntryTransaction(state, save, state::lock)
            val lifetime = DesktopSessionLifetime(clock, 1) { owner.close(); state.lock() }
            val pin = "9876".toCharArray()
            try {
                val accepted = if (kind == DesktopEntryTransaction.Kind.SET_PIN)
                    owner.submitPin(target, pin, lifetime::checkActive) { error("expired failure") }
                else owner.submit(target, kind, lifetime::checkActive) { error("expired failure") }
                assertTrue(accepted); worker.removeAt(0).run(); ui.removeAt(0).run()
                assertTrue(state.isLocked); assertFalse(gate.busy)
                if (kind == DesktopEntryTransaction.Kind.SET_PIN) assertTrue(pin.all { it == '\u0000' })
            } finally { lifetime.close(); owner.close(); state.lock(); pin.fill('\u0000') }
        }
    }
}
