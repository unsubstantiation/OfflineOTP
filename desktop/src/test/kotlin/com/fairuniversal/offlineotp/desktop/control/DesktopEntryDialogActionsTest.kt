package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import com.fairuniversal.offlineotp.ui.vault.VaultDialogKind
import com.fairuniversal.offlineotp.ui.vault.VaultDialogs
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEntryDialogActionsTest {
    private class Clock : ScreenTimerController.Scheduler {
        var now = 0L
        override fun elapsedRealtime() = now
        override fun postDelayed(runnable: Runnable, delayMillis: Long) {}
        override fun remove(runnable: Runnable) {}
    }
    private fun entry(kind: VaultDialogKind) = when (kind) {
        VaultDialogKind.PIN -> ManualEntryInput(type = Entry.OTPType.MOTP, label = "motp", secret = "0123456789abcdef", pin = "1234").build()
        else -> ManualEntryInput(type = Entry.OTPType.HOTP, label = "hotp", secret = "JBSWY3DPEHPK3PXP", timing = "0").build()
    }
    @Test fun realSavesCrossingDeadlinePersistButSuppressDialogCompletion() {
        for (kind in VaultDialogKind.values()) {
            val dir = Files.createTempDirectory("dialog-deadline-").toFile()
            val controller = VaultController(DesktopStorage.open(dir))
            val loaded = controller.createVault("password") as VaultController.Session.Loaded
            val state = DesktopVaultState(loaded.key); val dialogs = VaultDialogs(); val clock = Clock(); var deleted = false
            val target = entry(kind); assertTrue(EntryEditor(controller, state).add(target))
            val owner = DesktopEntryDialogActions(state, dialogs) { entries -> controller.save(state.key, entries).also { clock.now = 1000 } }
            val lifetime = DesktopSessionLifetime(clock, 1) { dialogs.close(); state.lock() }
            try {
                dialogs.open(kind, target.getListId()); val request = dialogs.pending!!
                val result = if (kind == VaultDialogKind.PIN) owner.submitPin(request, "9876", lifetime::checkActive)
                    else owner.confirm(request, lifetime::checkActive) { deleted = true }
                assertFalse(result); assertTrue(state.isLocked); assertTrue(dialogs.pending == null); assertFalse(deleted)
                val reopened = controller.openVault("password") as VaultController.Session.Loaded
                try {
                    when (kind) {
                        VaultDialogKind.DELETE -> assertTrue(reopened.entries.isEmpty())
                        VaultDialogKind.COUNTER -> assertEquals(1L, reopened.entries.single().getCounter())
                        VaultDialogKind.PIN -> assertEquals("9876", reopened.entries.single().getPin())
                    }
                } finally { DesktopUnlockOperation.discard(reopened) }
            } finally { lifetime.close(); state.lock(); dir.deleteRecursively() }
        }
    }
    @Test fun failedAndThrowingSavesAlsoLockWithoutRetryDialog() {
        for (kind in VaultDialogKind.values()) for (throwing in listOf(false, true)) {
            val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val dialogs = VaultDialogs(); val clock = Clock()
            val target = entry(kind); state.add(target)
            val owner = DesktopEntryDialogActions(state, dialogs) { clock.now = 1000; if (throwing) error("failure") else false }
            val lifetime = DesktopSessionLifetime(clock, 1) { dialogs.close(); state.lock() }
            try {
                dialogs.open(kind, target.getListId()); val request = dialogs.pending!!
                val result = if (kind == VaultDialogKind.PIN) owner.submitPin(request, "9876", lifetime::checkActive)
                    else owner.confirm(request, lifetime::checkActive) { error("late") }
                assertFalse(result); assertTrue(state.isLocked); assertTrue(dialogs.pending == null)
            } finally { lifetime.close(); state.lock() }
        }
    }
    @Test fun liveFailureCanRetryAndSuccessfulDeleteNotifiesOnce() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val dialogs = VaultDialogs()
        val entry = entry(VaultDialogKind.DELETE); state.add(entry); var save = false; var removed = 0
        val owner = DesktopEntryDialogActions(state, dialogs) { save }
        try {
            dialogs.open(VaultDialogKind.DELETE, entry.getListId()); val first = dialogs.pending!!
            assertFalse(owner.confirm(first, { true }) { removed++ })
            assertTrue(dialogs.pending!!.failed); assertEquals(1, state.snapshot().size)
            val retry = dialogs.pending!!; save = true
            assertTrue(owner.confirm(retry, { true }) { removed++ })
            assertEquals(1, removed); assertTrue(state.snapshot().isEmpty()); assertTrue(dialogs.pending == null)
            assertFalse(owner.confirm(retry, { true }) { removed++ }); assertEquals(1, removed)
        } finally { dialogs.close(); state.lock() }
    }

    @Test fun staleAndReentrantGuardsCannotAuthorizeSavesOrClearNewDialog() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val dialogs = VaultDialogs()
        val entry = entry(VaultDialogKind.COUNTER); state.add(entry); var writes = 0
        val owner = DesktopEntryDialogActions(state, dialogs) { writes++; dialogs.open(VaultDialogKind.DELETE, entry.getListId()); false }
        try {
            dialogs.open(VaultDialogKind.COUNTER, entry.getListId()); val old = dialogs.pending!!
            assertFalse(owner.confirm(old, { dialogs.open(VaultDialogKind.COUNTER, entry.getListId()); true }) {})
            assertEquals(0, writes)
            val current = dialogs.pending!!
            assertFalse(owner.confirm(old, { true }) {})
            assertFalse(owner.confirm(current, { false }) {})
            assertFalse(owner.submitPin(current, "1234") { true })
            assertFalse(owner.confirm(current, { true }) {})
            assertEquals(1, writes); assertEquals(VaultDialogKind.DELETE, dialogs.pending!!.kind)
            assertFalse(dialogs.pending!!.failed); assertEquals(0L, entry.getCounter())
        } finally { dialogs.close(); state.lock() }
    }
}
