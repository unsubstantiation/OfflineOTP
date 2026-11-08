package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import com.fairuniversal.offlineotp.ui.vault.VaultCards
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEntryCopyTest {
    private class Harness : AutoCloseable {
        val dir = Files.createTempDirectory("compose-copy-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("test-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val editor = EntryEditor(controller, state)
        var clipboardValue = ""
        var clipboardFails = false
        var now = 1_000L
        val clipboard = ExpiringClipboard(object : ScreenTimerController.Scheduler {
            override fun elapsedRealtime() = now
            override fun postDelayed(runnable: Runnable, delayMillis: Long) {}
            override fun remove(runnable: Runnable) {}
        }, object : ExpiringClipboard.Port {
            override fun read() = clipboardValue
            override fun write(text: String) { check(!clipboardFails); clipboardValue = text }
        })
        val copier = DesktopEntryCopy(state, editor, clipboard) { now }
        val entry = ManualEntryInput(issuer = "Example", label = "alice", secret = "JBSWY3DPEHPK3PXP").build()
        init { check(editor.add(entry)) }
        override fun close() { dir.setWritable(true, false); clipboardFails = false; copier.close(); state.lock(); dir.deleteRecursively() }
    }

    @Test
    fun noteCopyPreservesWhitespaceAndRejectsUnsupportedFields() {
        val h = Harness()
        try {
            val note = ManualEntryInput(type = Entry.OTPType.NOTE, label = "Recovery", secret = "  line one\nline two  ").build()
            h.editor.add(note)
            val card = VaultCards.of(note, 0L, revealed = true)
            val frame = h.copier.bind(listOf(card))
            val request = h.copier.open(frame, card, true)!!
            assertEquals(DesktopEntryCopy.Result.UNAVAILABLE, h.copier.copy(request, EntryEditor.CopyField.PASSWORD, true))
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copy(request, EntryEditor.CopyField.TOKEN_OR_NOTE, true))
            assertEquals("  line one\nline two  ", h.clipboardValue)
            h.copier.close()
            assertEquals("", h.clipboardValue)
            assertTrue(h.copier.open(frame, card, true) == null)
        } finally { h.close() }
    }

    @Test
    fun copiesLiveCodeNotFrameTextAndPersistsUsageWithoutChangingPrivacy() {
        val h = Harness()
        try {
            val card = VaultCards.of(h.entry, 1_000L, revealed = true).copy(token = "not-the-token")
            val frame = h.copier.bind(listOf(card))
            val request = h.copier.open(frame, card, true)!!
            h.now = 61_000L
            val frequency = h.entry.getUsedFrequency()
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copy(request, EntryEditor.CopyField.TOKEN_OR_NOTE, true))
            assertEquals(h.entry.getCurrentOTP(), h.clipboardValue)
            assertEquals(frequency + 1, h.entry.getUsedFrequency())
            assertFalse(h.entry.areDetailsVisible())
            val reopened = h.controller.openVault("test-password") as VaultController.Session.Loaded
            val persisted = DesktopVaultState(reopened.key)
            persisted.load(reopened.entries)
            try { assertEquals(frequency + 1, persisted.snapshot().single().getUsedFrequency()) }
            finally { persisted.lock() }
        } finally { h.close() }
    }

    @Test
    fun failedPersistenceRollsBackUsageAndDoesNotTouchClipboard() {
        val h = Harness()
        try {
            val card = VaultCards.of(h.entry, 1_000L, revealed = true)
            val request = h.copier.open(h.copier.bind(listOf(card)), card, true)!!
            val before = h.entry.getLastUsed() to h.entry.getUsedFrequency()
            assertTrue(h.dir.setWritable(false, false))
            assertEquals(DesktopEntryCopy.Result.SAVE_FAILED, h.copier.copy(request, EntryEditor.CopyField.TOKEN_OR_NOTE, true))
            assertEquals("", h.clipboardValue)
            assertEquals(before, h.entry.getLastUsed() to h.entry.getUsedFrequency())
        } finally { h.close() }
    }

    @Test
    fun replacedHiddenDismissedAndLockedTargetsCannotCopy() {
        val h = Harness()
        try {
            val card = VaultCards.of(h.entry, 1_000L, revealed = true)
            val frame = h.copier.bind(listOf(card))
            assertTrue(h.copier.open(frame, card.copy(), true) == null) // Same value is not frame authority.
            assertTrue(h.copier.open(frame, card, false) == null)
            val request = h.copier.open(frame, card, true)!!
            assertEquals(DesktopEntryCopy.Result.UNAVAILABLE, h.copier.copy(request, EntryEditor.CopyField.TOKEN_OR_NOTE, false))
            h.copier.dismiss(request)
            assertEquals(DesktopEntryCopy.Result.UNAVAILABLE, h.copier.copy(request, EntryEditor.CopyField.TOKEN_OR_NOTE, true))
            val replacement = ManualEntryInput.fromEntry(h.entry).build(h.entry)
            h.state.replace(h.entry, replacement)
            assertTrue(h.copier.open(frame, card, true) == null)
            val newCard = VaultCards.of(replacement, 1_000L, revealed = true)
            val newRequest = h.copier.open(h.copier.bind(listOf(newCard)), newCard, true)!!
            h.state.lock()
            assertEquals(DesktopEntryCopy.Result.UNAVAILABLE, h.copier.copy(newRequest, EntryEditor.CopyField.TOKEN_OR_NOTE, true))
            assertEquals("", h.clipboardValue)
        } finally { h.close() }
    }

    @Test
    fun sshCopiesOnlyTheExplicitlySelectedFieldAndReportsClipboardFailure() {
        val h = Harness()
        try {
            val ssh = ManualEntryInput(type = Entry.OTPType.SSH_KEY, label = "server", secret = "private-key",
                publicKey = "public-key", sshPassword = "connection-password").build()
            h.editor.add(ssh)
            val card = VaultCards.of(ssh, 0L, revealed = true)
            val frame = h.copier.bind(listOf(card))
            for ((field, expected) in listOf(EntryEditor.CopyField.SSH_PUBLIC_KEY to "public-key",
                    EntryEditor.CopyField.SSH_PRIVATE_KEY to "private-key", EntryEditor.CopyField.SSH_PASSWORD to "connection-password")) {
                val request = h.copier.open(frame, card, true)!!
                assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copy(request, field, true))
                assertEquals(expected, h.clipboardValue)
            }
            val request = h.copier.open(frame, card, true)!!
            h.clipboardFails = true
            assertEquals(DesktopEntryCopy.Result.CLIPBOARD_FAILED, h.copier.copy(request, EntryEditor.CopyField.SSH_PUBLIC_KEY, true))
            assertEquals("connection-password", h.clipboardValue)
        } finally { h.close() }
    }
}
