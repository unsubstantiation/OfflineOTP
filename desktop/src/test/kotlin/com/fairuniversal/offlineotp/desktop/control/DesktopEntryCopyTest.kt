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

    @Test fun concealedOtpAndNoteCopyWithoutReveal() {
        val h = Harness()
        try {
            val card = VaultCards.of(h.entry, h.now, revealed = false).copy(token = "stale")
            val request = h.copier.open(h.copier.bind(listOf(card)), card, false)!!
            h.now += 60_000
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copy(request, EntryEditor.CopyField.TOKEN_OR_NOTE, false))
            assertEquals(h.entry.getCurrentOTP(), h.clipboardValue)
            assertFalse(card.revealed)
            val note = ManualEntryInput(type = Entry.OTPType.NOTE, label = "note", secret = "private").build()
            assertTrue(h.editor.add(note))
            val hidden = VaultCards.of(note, h.now, revealed = false)
            val noteRequest = h.copier.open(h.copier.bind(listOf(hidden)), hidden, false)!!
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copy(noteRequest, EntryEditor.CopyField.TOKEN_OR_NOTE, false))
            assertEquals("private", h.clipboardValue)
            assertFalse(hidden.revealed)
        } finally { h.close() }
    }

    @Test fun expiryAfterPersistenceLocksWithoutPublishingCapturedSecret() {
        val h = Harness()
        val lifetime = DesktopSessionLifetime(object : ScreenTimerController.Scheduler {
            override fun elapsedRealtime() = h.now
            override fun postDelayed(runnable: Runnable, delayMillis: Long) {}
            override fun remove(runnable: Runnable) {}
        }, 30) { h.copier.close(); h.state.lock() }
        try {
            val before = h.entry.getUsedFrequency()
            val card = VaultCards.of(h.entry, h.now, revealed = true)
            val request = h.copier.open(h.copier.bind(listOf(card)), card, true)!!
            var checks = 0
            assertEquals(DesktopEntryCopy.Result.UNAVAILABLE, h.copier.copy(request, EntryEditor.CopyField.TOKEN_OR_NOTE, true) {
                if (++checks == 2) h.now += 30_000 // Simulate the synchronous write crossing its deadline.
                lifetime.checkActive()
            })
            assertTrue(h.state.isLocked); assertEquals("", h.clipboardValue)
            val reopened = h.controller.openVault("test-password") as VaultController.Session.Loaded
            try { assertEquals(before + 1, reopened.entries.single().getUsedFrequency()) }
            finally { DesktopUnlockOperation.discard(reopened) }
        } finally { lifetime.close(); h.close() }
    }

    @Test fun failedPersistenceStillChecksDeadlineAndReentrantGuardCannotStealCopyAuthority() {
        val h = Harness()
        try {
            val card = VaultCards.of(h.entry, h.now, revealed = true)
            val frame = h.copier.bind(listOf(card)); val request = h.copier.open(frame, card, true)!!
            var replacement: DesktopEntryCopy.Request? = null
            assertEquals(DesktopEntryCopy.Result.UNAVAILABLE, h.copier.copy(request, EntryEditor.CopyField.TOKEN_OR_NOTE, true) {
                replacement = h.copier.open(frame, card, true); true
            })
            assertTrue(h.copier.pending === replacement); assertEquals("", h.clipboardValue)
            assertTrue(h.dir.setWritable(false, false)); var checks = 0
            assertEquals(DesktopEntryCopy.Result.UNAVAILABLE, h.copier.copy(replacement!!, EntryEditor.CopyField.TOKEN_OR_NOTE, true) {
                if (++checks == 2) { h.copier.close(); h.state.lock(); false } else true
            })
            assertEquals(2, checks); assertTrue(h.state.isLocked); assertEquals("", h.clipboardValue)
        } finally { h.close() }
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
    fun replacedDismissedAndLockedTargetsCannotCopy() {
        val h = Harness()
        try {
            val card = VaultCards.of(h.entry, 1_000L, revealed = true)
            val frame = h.copier.bind(listOf(card))
            assertTrue(h.copier.open(frame, card.copy(), true) == null) // Same value is not frame authority.
            val request = h.copier.open(frame, card, true)!!
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

    @Test
    fun concealedAndRevealedEntriesCopyAccountAndPasswordWithoutReveal() {
        val h = Harness()
        try {
            val otpWithPw = ManualEntryInput(
                issuer = "GitHub",
                label = "alice@example.com",
                hiddenLabel = "otp-pw-secret",
                secret = "JBSWY3DPEHPK3PXP"
            ).build()
            assertTrue(h.editor.add(otpWithPw))
            val hiddenOtpCard = VaultCards.of(otpWithPw, 0L, revealed = false)
            assertTrue(hiddenOtpCard.canCopyAccount)
            assertTrue(hiddenOtpCard.canCopyPassword)
            val otpFrame = h.copier.bind(listOf(hiddenOtpCard))
            val otpAccReq = h.copier.openAccount(otpFrame, hiddenOtpCard)!!
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copy(otpAccReq, EntryEditor.CopyField.ACCOUNT, false))
            assertEquals("alice@example.com", h.clipboardValue)
            val otpPwReq = h.copier.openPassword(otpFrame, hiddenOtpCard)!!
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copy(otpPwReq, EntryEditor.CopyField.ENTRY_PASSWORD, false))
            assertEquals("otp-pw-secret", h.clipboardValue)

            val emailAccount = com.fairuniversal.offlineotp.Utilities.EmailMaterial.create(
                name = "Ops Mail",
                address = "ops@example.com",
                incomingHost = "imap.example.com",
                incomingPort = 993,
                incomingEncryption = 0,
                incomingProtocol = 0,
                incomingUser = "ops@example.com",
                incomingPassword = "in-secret",
                incomingAuth = 1,
                outgoingHost = "smtp.example.com",
                outgoingPort = 465,
                outgoingEncryption = 0,
                outgoingUser = "ops@example.com",
                outgoingPassword = "out-secret",
                outgoingAuth = 1
            )
            val email = Entry.email(emailAccount.account, emailAccount.identity, emailAccount.identities, arrayListOf("ops"))
            assertTrue(h.editor.add(email))
            val hiddenEmailCard = VaultCards.of(email, 0L, revealed = false)
            assertTrue(hiddenEmailCard.canCopyAccount)
            assertTrue(hiddenEmailCard.canCopyPassword)
            val emailFrame = h.copier.bind(listOf(hiddenEmailCard))

            val emailAccReq = h.copier.openAccount(emailFrame, hiddenEmailCard)!!
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copy(emailAccReq, EntryEditor.CopyField.ACCOUNT, false))
            assertEquals("ops@example.com", h.clipboardValue)

            val emailPwReq = h.copier.openPassword(emailFrame, hiddenEmailCard)!!
            assertEquals(listOf("Incoming password", "Outgoing password"), emailPwReq.passwordChoiceLabels)
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copyPasswordChoice(emailPwReq, 0))
            assertEquals("in-secret", h.clipboardValue)
            val emailPwReq2 = h.copier.openPassword(emailFrame, hiddenEmailCard)!!
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copyPasswordChoice(emailPwReq2, 1))
            assertEquals("out-secret", h.clipboardValue)

            val emailReq1 = h.copier.open(emailFrame, hiddenEmailCard, false)!!
            assertEquals(
                listOf(EntryEditor.CopyField.EMAIL_INCOMING_PASSWORD, EntryEditor.CopyField.EMAIL_OUTGOING_PASSWORD),
                emailReq1.fields
            )
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copy(emailReq1, EntryEditor.CopyField.EMAIL_INCOMING_PASSWORD, false))
            assertEquals("in-secret", h.clipboardValue)

            val passwordCsv = "name,url,username,password\r\nGitLab,gitlab.example.com,alice,pw-secret\r\n".toByteArray()
            val pw = com.fairuniversal.offlineotp.Utilities.PasswordImport.prepare(
                passwordCsv,
                "logins.csv",
                com.fairuniversal.offlineotp.Utilities.PasswordImport.Mode.INDIVIDUAL
            ).single()
            assertTrue(h.editor.add(pw))
            val hiddenPwCard = VaultCards.of(pw, 0L, revealed = false)
            assertTrue(hiddenPwCard.canCopyAccount)
            assertTrue(hiddenPwCard.canCopyPassword)
            val pwFrame = h.copier.bind(listOf(hiddenPwCard))
            val pwAccReq = h.copier.openAccount(pwFrame, hiddenPwCard)!!
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copy(pwAccReq, EntryEditor.CopyField.ACCOUNT, false))
            assertEquals("alice", h.clipboardValue)
            val pwDirectReq = h.copier.openPassword(pwFrame, hiddenPwCard)!!
            assertEquals(DesktopEntryCopy.Result.COPIED, h.copier.copy(pwDirectReq, EntryEditor.CopyField.ENTRY_PASSWORD, false))
            assertEquals("pw-secret", h.clipboardValue)
        } finally { h.close() }
    }
}
