package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEntryTransactionTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { tasks.removeAt(0).run() }
    }
    private fun entry(label: String) = ManualEntryInput(label = label, secret = "JBSWY3DPEHPK3PXP").build()

    private fun motp() = ManualEntryInput(type = com.fairuniversal.offlineotp.Database.Entry.OTPType.MOTP,
        label = "motp", secret = "0123456789abcdef", pin = "1234").build()

    @Test fun pinPersistsBeforeAdoptionAndOwnedBufferIsWiped() {
        val dir = Files.createTempDirectory("async-pin-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key); val target = motp(); state.add(target)
        val worker = Queue(); val ui = Queue(); val pin = "9876".toCharArray()
        val owner = DesktopEntryTransaction(state, controller.detachedSave(worker, { ui.execute(it) }), state::lock)
        try {
            var result: DesktopEntryTransaction.Result? = null
            assertTrue(owner.submitPin(target, pin, { true }) { result = it })
            val duplicate = "1111".toCharArray()
            assertFalse(owner.submitPin(target, duplicate, { true }) {}); assertTrue(duplicate.all { it == '\u0000' })
            worker.run(); assertEquals("1234", target.getPin())
            ui.run(); assertEquals("9876", target.getPin()); assertEquals(DesktopEntryTransaction.Result.PIN_SAVED, result)
            assertTrue(pin.all { it == '\u0000' })
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try { assertEquals("9876", reopened.entries.single().getPin()) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { owner.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun pinFailureAndQueuedCancellationWipeInputWithoutChangingLivePin() {
        for (cancel in listOf(false, true)) {
            val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val target = motp(); state.add(target)
            val worker = Queue(); val ui = Queue(); val pin = "9876".toCharArray()
            val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { ui.execute(it) }) { _, candidate ->
                assertEquals("9876", candidate.single().getPin()); false
            }, state::lock)
            try {
                var result: DesktopEntryTransaction.Result? = null
                assertTrue(owner.submitPin(target, pin, { true }) { result = it })
                if (cancel) owner.close()
                worker.run(); while (ui.tasks.isNotEmpty()) ui.run()
                assertTrue(pin.all { it == '\u0000' })
                if (cancel) { assertTrue(state.isLocked); assertTrue(result == null) }
                else { assertEquals("1234", target.getPin()); assertEquals(DesktopEntryTransaction.Result.SAVE_FAILED, result) }
            } finally { owner.close(); state.lock() }
        }
    }
    @Test fun invalidPinAndWrongEntryKindNeverQueueAndStillConsumeInput() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val target = motp(); val other = entry("totp")
        state.add(target); state.add(other); val worker = Queue()
        val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { it.run() }) { _, _ -> error("invalid") }, state::lock)
        try {
            for ((entry, pin) in listOf(target to charArrayOf(), target to CharArray(1025) { '1' }, other to "1234".toCharArray())) {
                assertFalse(owner.submitPin(entry, pin, { true }) {}); assertTrue(pin.all { it == '\u0000' })
            }
            assertTrue(worker.tasks.isEmpty()); assertFalse(owner.busy)
        } finally { owner.close(); state.lock() }
    }

    @Test fun counterCandidatePersistsBeforeLiveCounterChangesAndCannotSubmitTwice() {
        val dir = Files.createTempDirectory("async-counter-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val target = ManualEntryInput(type = com.fairuniversal.offlineotp.Database.Entry.OTPType.HOTP,
            label = "counter", secret = "JBSWY3DPEHPK3PXP", timing = "0").build()
        state.add(target); assertTrue(controller.save(state.key, state.snapshot()))
        val worker = Queue(); val ui = Queue()
        val owner = DesktopEntryTransaction(state, controller.detachedSave(worker, { ui.execute(it) }), state::lock)
        try {
            var result: DesktopEntryTransaction.Result? = null
            assertTrue(owner.submit(target, DesktopEntryTransaction.Kind.ADVANCE_COUNTER, { true }) { result = it })
            assertFalse(owner.submit(target, DesktopEntryTransaction.Kind.ADVANCE_COUNTER, { true }) {})
            worker.run(); assertEquals(0L, target.getCounter())
            ui.run(); assertEquals(1L, target.getCounter())
            assertEquals(DesktopEntryTransaction.Result.COUNTER_ADVANCED, result)
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try { assertEquals(1L, reopened.entries.single().getCounter()) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { owner.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun counterFailureDoesNotAdvanceAndInvalidTargetsNeverQueue() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val target = ManualEntryInput(type = com.fairuniversal.offlineotp.Database.Entry.OTPType.HOTP,
            label = "counter", secret = "JBSWY3DPEHPK3PXP", timing = "0").build()
        state.add(target); val worker = Queue(); val ui = Queue()
        val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { ui.execute(it) }) { _, candidate ->
            assertEquals(1L, candidate.single().getCounter()); false
        }, state::lock)
        try {
            var result: DesktopEntryTransaction.Result? = null
            owner.submit(target, DesktopEntryTransaction.Kind.ADVANCE_COUNTER, { true }) { result = it }
            worker.run(); ui.run()
            assertEquals(DesktopEntryTransaction.Result.SAVE_FAILED, result); assertEquals(0L, target.getCounter())
            target.setCounter(com.fairuniversal.offlineotp.Utilities.EntryUsage.MAX_COUNTER)
            assertFalse(owner.submit(target, DesktopEntryTransaction.Kind.ADVANCE_COUNTER, { true }) {})
            val totp = entry("time based"); state.add(totp)
            assertFalse(owner.submit(totp, DesktopEntryTransaction.Kind.ADVANCE_COUNTER, { true }) {})
            assertTrue(worker.tasks.isEmpty()); assertFalse(owner.busy)
        } finally { owner.close(); state.lock() }
    }

    @Test fun actualSavePrecedesLiveDeletionAndReopensWithoutTarget() {
        val dir = Files.createTempDirectory("async-delete-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key); val target = entry("remove"); val kept = entry("keep")
        state.add(target); state.add(kept); assertTrue(controller.save(state.key, state.snapshot()))
        val worker = Queue(); val ui = Queue()
        val owner = DesktopEntryTransaction(state, controller.detachedSave(worker, { ui.execute(it) }), state::lock)
        var result: DesktopEntryTransaction.Result? = null
        try {
            assertTrue(owner.submit(target, { true }) { result = it })
            assertEquals(2, state.snapshot().size); assertTrue(owner.busy)
            assertFalse(owner.submit(kept, { true }) {})
            worker.run(); assertEquals(2, state.snapshot().size)
            ui.run(); assertEquals(DesktopEntryTransaction.Result.DELETED, result)
            assertTrue(state.snapshot().single() === kept); assertFalse(owner.busy)
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try { assertEquals("keep", reopened.entries.single().getLabel()) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { owner.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun failedWriteKeepsOriginalAndCanRetry() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val target = entry("keep")
        state.add(target); val worker = Queue(); val ui = Queue(); var succeeds = false
        val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { ui.execute(it) }) { _, entries ->
            assertTrue(entries.isEmpty()); succeeds
        }, state::lock)
        try {
            var result: DesktopEntryTransaction.Result? = null
            owner.submit(target, { true }) { result = it }; worker.run(); ui.run()
            assertEquals(DesktopEntryTransaction.Result.SAVE_FAILED, result)
            assertTrue(state.snapshot().single() === target); assertFalse(state.isLocked)
            succeeds = true; owner.submit(target, { true }) { result = it }; worker.run(); ui.run()
            assertEquals(DesktopEntryTransaction.Result.DELETED, result); assertTrue(state.snapshot().isEmpty())
        } finally { owner.close(); state.lock() }
    }
    @Test fun expiredOrChangedSessionLocksInsteadOfAdoptingCandidate() {
        for (change in listOf(false, true)) {
            val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val target = entry("target")
            state.add(target); val worker = Queue(); val ui = Queue(); var active = true
            val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { ui.execute(it) }) { _, _ -> true }, state::lock)
            try {
                owner.submit(target, { active }) { error("stale adoption") }; worker.run()
                if (change) state.add(entry("new")) else active = false
                ui.run(); assertTrue(state.isLocked)
            } finally { owner.close(); state.lock() }
        }
    }
    @Test fun inPlaceEditsToTargetOrRetainedEntryInvalidateResult() {
        for (editTarget in listOf(true, false)) {
            val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
            val target = entry("target"); val kept = entry("kept")
            state.add(target); state.add(kept)
            val worker = Queue(); val ui = Queue()
            val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { ui.execute(it) }) { _, _ -> true }, state::lock)
            try {
                assertTrue(owner.submit(target, { true }) { error("stale result") })
                worker.run()
                (if (editTarget) target else kept).setLabel("changed without replacement")
                ui.run(); assertTrue(state.isLocked)
            } finally { owner.close(); state.lock() }
        }
    }
    @Test fun tokenRefreshDoesNotInvalidatePersistedContentBaseline() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val target = entry("target"); val kept = entry("kept")
        state.add(target); state.add(kept)
        val worker = Queue(); val ui = Queue()
        val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { ui.execute(it) }) { _, _ -> true }, state::lock)
        try {
            var deleted = false
            owner.submit(target, { true }) { deleted = it == DesktopEntryTransaction.Result.DELETED }
            worker.run(); kept.updateOTPAt(120L, false); ui.run()
            assertTrue(deleted); assertFalse(state.isLocked); assertTrue(state.snapshot().single() === kept)
        } finally { owner.close(); state.lock() }
    }

    @Test fun failedCompletionCanBeRecoveredByLockAndDisposalWithoutStrandingController() {
        val dir = Files.createTempDirectory("delete-completion-recovery-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key); val target = entry("target")
        state.add(target); assertTrue(controller.save(state.key, state.snapshot()))
        val worker = Queue(); val ui = Queue()
        val owner = DesktopEntryTransaction(state, controller.detachedSave(worker, { ui.execute(it) }), state::lock)
        try {
            assertTrue(owner.submit(target, { true }) { error("UI failed") })
            worker.run()
            var threw = false
            try { ui.run() } catch (_: IllegalStateException) { threw = true }
            assertTrue(threw); assertTrue(controller.persistenceBusy)
            assertTrue(state.snapshot().isEmpty()) // The successful disk write was adopted first.
            state.lock(); owner.close()
            assertFalse(controller.persistenceBusy)
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try { assertTrue(reopened.entries.isEmpty()) } finally { DesktopUnlockOperation.discard(reopened) }
        } finally { owner.close(); state.lock(); dir.deleteRecursively() }
    }

    @Test fun closingQueuedTransactionLocksAndCancelsWrite() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val target = entry("target")
        state.add(target); val worker = Queue()
        val owner = DesktopEntryTransaction(state, DesktopDetachedSave(worker, { it.run() }) { _, _ -> error("canceled write") }, state::lock)
        owner.submit(target, { true }) { error("late") }; owner.close(); worker.run()
        assertTrue(state.isLocked); assertFalse(owner.busy)
    }
}
