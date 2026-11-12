package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import com.fairuniversal.offlineotp.ui.vault.VaultCards
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEntryMoveTest {
    private fun entry(label: String) = ManualEntryInput(label = label, secret = "JBSWY3DPEHPK3PXP").build()

    @Test fun expiredOrReentrantGuardsCannotPersistMoves() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val a = entry("a"); val b = entry("b"); state.add(a); state.add(b)
        val cards = listOf(a, b).map { VaultCards.of(it, 0L) }
        var writes = 0
        val mover = DesktopEntryMove(state) { _, _ -> writes++; true }
        val frame = mover.bind(cards)
        try {
            assertFalse(mover.move(frame, cards[0], 1) { false })
            assertFalse(mover.move(frame, cards[0], 1) { state.replace(b, entry("replacement")); true })
            assertEquals(0, writes)
        } finally { state.lock() }
    }

    @Test fun slowSuccessfulFailedAndThrowingMovesCheckExpiryBeforeCompletion() {
        for (outcome in 0..2) {
            val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
            val a = entry("a"); val b = entry("b"); state.add(a); state.add(b)
            val cards = listOf(a, b).map { VaultCards.of(it, 0L) }
            var now = 0L
            val clock = object : com.fairuniversal.offlineotp.Utilities.ScreenTimerController.Scheduler {
                override fun elapsedRealtime() = now
                override fun postDelayed(runnable: Runnable, delayMillis: Long) {}
                override fun remove(runnable: Runnable) {}
            }
            val lifetime = DesktopSessionLifetime(clock, 30) { state.lock() }
            var writes = 0
            val mover = DesktopEntryMove(state) { _, _ ->
                writes++; now = 30_000
                when (outcome) { 0 -> true; 1 -> false; else -> error("write failed") }
            }
            try {
                val frame = mover.bind(cards)
                assertFalse(mover.move(frame, cards[0], 1, lifetime::checkActive))
                assertEquals(1, writes); assertTrue(state.isLocked)
                assertFalse(lifetime.checkActive())
            } finally { lifetime.close(); state.lock() }
        }
    }

    @Test
    fun movePersistsManualOrderAndStaleNeighborCannotMoveAgain() {
        val dir = Files.createTempDirectory("compose-move-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("test-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        try {
            val editor = EntryEditor(controller, state)
            val a = entry("a"); val b = entry("b"); val c = entry("c")
            listOf(a, b, c).forEach { assertTrue(editor.add(it)) }
            val mover = DesktopEntryMove(state, editor::move)
            val cards = listOf(a, b, c).map { VaultCards.of(it, 0L) }
            val frame = mover.bind(cards)
            assertTrue(mover.move(frame, cards[0], 1))
            assertEquals(listOf("b", "a", "c"), state.snapshot().map { it.getLabel() })
            assertFalse(mover.move(frame, cards[0], 1)) // The original neighbor is no longer below a.
            val reopened = controller.openVault("test-password") as VaultController.Session.Loaded
            val check = DesktopVaultState(reopened.key)
            check.load(reopened.entries)
            try { assertEquals(listOf("b", "a", "c"), check.snapshot().map { it.getLabel() }) }
            finally { check.lock() }
        } finally { state.lock(); dir.deleteRecursively() }
    }

    @Test
    fun filterSortReplacementAndLockInvalidateOldMoves() {
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
        val a = entry("a"); val b = entry("b")
        state.add(a); state.add(b)
        var saves = 0
        val mover = DesktopEntryMove(state) { _, _ -> saves++; true }
        val cards = listOf(a, b).map { VaultCards.of(it, 0L) }
        val frame = mover.bind(cards)
        try {
            assertFalse(mover.move(frame, cards[0].copy(), 1))
            assertFalse(mover.move(frame, cards[0], -1))
            assertFalse(mover.move(frame, cards[0], 2))
            state.applySearch("a")
            assertFalse(mover.move(frame, cards[0], 1))
            state.applySearch("")
            state.applySortMode(Constants.SortMode.LABEL)
            assertFalse(mover.move(frame, cards[0], 1))
            state.applySortMode(Constants.SortMode.UNSORTED)
            state.replace(b, entry("new neighbor"))
            assertFalse(mover.move(frame, cards[0], 1))
            state.lock()
            assertFalse(mover.move(frame, cards[0], 1))
            assertEquals(0, saves)
        } finally { state.lock() }
    }

    @Test
    fun failedWriteLeavesOrderUnchanged() {
        val dir = Files.createTempDirectory("compose-move-failure-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("test-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        try {
            val editor = EntryEditor(controller, state)
            val a = entry("a"); val b = entry("b")
            editor.add(a); editor.add(b)
            val mover = DesktopEntryMove(state, editor::move)
            val cards = listOf(a, b).map { VaultCards.of(it, 0L) }
            val frame = mover.bind(cards)
            assertTrue(dir.setWritable(false, false))
            assertFalse(mover.move(frame, cards[0], 1))
            assertEquals(listOf("a", "b"), state.snapshot().map { it.getLabel() })
        } finally { dir.setWritable(true, false); state.lock(); dir.deleteRecursively() }
    }
}
