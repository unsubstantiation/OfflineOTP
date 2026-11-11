package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.EmailEntryInput
import com.fairuniversal.offlineotp.ui.vault.EmailPasswordInput
import com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEntryEditingTest {
    private val input = ManualEntryInput(secret = "JBSWY3DPEHPK3PXP", issuer = "Example", label = "alice")
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))

    private fun email() = EmailEntryInput(address = "alice@example.com", incomingHost = "imap.example.com",
        outgoingHost = "smtp.example.com", incomingPassword = "incoming", outgoingPassword = "outgoing").build()

    @Test fun emailCredentialsPersistOnlyWithCurrentRevealedAuthority() {
        val dir = Files.createTempDirectory("compose-email-edit-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val editor = EntryEditor(controller, state)
        val edits = DesktopEntryEditing(state, editor::replace)
        try {
            val original = email(); assertTrue(editor.add(original))
            edits.open(original.getListId()); val request = edits.pending!!
            assertTrue(request.isEmail); assertTrue(edits.initialInput(request) == null)
            val input = edits.initialEmailPasswords(request)!!.copy(incoming = "new credential")
            assertFalse(edits.submitEmailPasswords(request, input, false))
            assertTrue(edits.submitEmailPasswords(request, input, true))
            assertFalse(edits.submitEmailPasswords(request, input, true))
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try {
                val account = reopened.entries.single().getEmailAccount()
                assertEquals("new credential", account.account.getString("password"))
                assertEquals("outgoing", account.identity.getString("password"))
            } finally { DesktopUnlockOperation.discard(reopened) }
            edits.open(state.snapshot().single().getListId()); val locked = edits.pending!!
            state.lock(); assertFalse(edits.submitEmailPasswords(locked, input, true))
        } finally { edits.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun failedEmailEditDestroysCandidateAndStaleOrRemovedTargetsCannotSave() {
        val state = state(); val original = email(); state.add(original)
        var candidate: Entry? = null
        val edits = DesktopEntryEditing(state) { _, next -> candidate = next; false }
        try {
            edits.open(original.getListId()); val first = edits.pending!!
            val input = EmailPasswordInput("new", listOf(""))
            assertFalse(edits.submitEmailPasswords(first, input, true))
            assertEquals(0, candidate!!.getSecretSize())
            assertEquals("incoming", original.getEmailAccount().account.getString("password"))
            candidate = null
            edits.open(original.getListId()); val second = edits.pending!!
            assertFalse(edits.submitEmailPasswords(first, input, true))
            edits.dismiss(second); assertFalse(edits.submitEmailPasswords(second, input, true))
            edits.open(original.getListId()); val third = edits.pending!!
            state.replace(original, email())
            assertFalse(edits.submitEmailPasswords(third, input, true))
            assertTrue(candidate == null)
        } finally { edits.close(); state.lock() }
    }

    @Test
    fun passwordEditsAreGuardedPersistedAndPreserveUneditedColumns() {
        val dir = Files.createTempDirectory("compose-password-edit-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("test-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        try {
            val original = Entry.passwordFields("alice", "site", listOf("url" to "https://old", "username" to "alice",
                "password" to "secret", "notes" to "preserved"))
            val editor = EntryEditor(controller, state)
            assertTrue(editor.add(original))
            val edits = DesktopEntryEditing(state, editor::replace)
            edits.open(original.getListId()); val request = edits.pending!!
            assertTrue(request.isPassword)
            assertTrue(edits.initialInput(request) == null)
            val input = edits.initialPasswordInput(request)!!.copy(username = "bob", password = "changed")
            assertEquals("", edits.initialPasswordInput(request)!!.password)
            assertFalse(edits.submitPassword(request, input, false))
            assertTrue(edits.submitPassword(request, input, true))
            assertFalse(edits.submitPassword(request, input, true))
            val reopened = controller.openVault("test-password") as VaultController.Session.Loaded
            try {
                val fields = reopened.entries.single().getPasswordFields()
                assertEquals("bob", fields.value("username"))
                assertEquals("changed", fields.value("password"))
                assertEquals("preserved", fields.value("notes"))
            } finally { DesktopUnlockOperation.discard(reopened) }
            edits.open(state.snapshot().single().getListId()); val locked = edits.pending!!
            state.lock()
            assertFalse(edits.submitPassword(locked, input, true))
        } finally { state.lock(); dir.deleteRecursively() }
    }

    @Test
    fun failedPasswordEditKeepsOriginalAndDestroysCandidate() {
        val state = state()
        val original = Entry.passwordFields("alice", "site", listOf("url" to "site", "username" to "alice", "password" to "secret"))
        state.add(original)
        var candidate: Entry? = null
        val edits = DesktopEntryEditing(state) { _, next -> candidate = next; false }
        try {
            edits.open(original.getListId()); val request = edits.pending!!
            assertFalse(edits.submitPassword(request, PasswordEntryInput("new", "bob", "changed"), true))
            assertEquals(0, candidate!!.getSecretSize())
            assertTrue(state.snapshot().single() === original)
            assertEquals("secret", original.getPasswordFields().value("password"))
        } finally { edits.close(); state.lock() }
    }

    @Test
    fun optionalSecretRemovalPersistsAndFailedSaveKeepsOriginal() {
        val dir = Files.createTempDirectory("compose-clear-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("test-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        try {
            val editor = EntryEditor(controller, state)
            val original = ManualEntryInput(type = Entry.OTPType.MOTP, secret = "0123456789abcdef",
                pin = "1234", hiddenLabel = "protected").build()
            assertTrue(editor.add(original))
            val failing = DesktopEntryEditing(state) { _, _ -> false }
            failing.open(original.getListId())
            val failed = failing.pending!!
            assertFalse(failing.submit(failed, failing.initialInput(failed)!!.copy(clearPin = true, clearHiddenLabel = true), true))
            assertEquals("1234", original.getPin())
            assertEquals("protected", original.getHiddenLabel())
            failing.close()
            val edits = DesktopEntryEditing(state, editor::replace)
            edits.open(original.getListId())
            val request = edits.pending!!
            val clear = edits.initialInput(request)!!.copy(clearPin = true, clearHiddenLabel = true)
            assertFalse(edits.submit(request, clear, false))
            assertTrue(edits.submit(request, clear, true))
            assertFalse(edits.submit(request, clear, true))
            val reopened = controller.openVault("test-password") as VaultController.Session.Loaded
            try {
                assertEquals("", reopened.entries.single().getPin())
                assertEquals("", reopened.entries.single().getHiddenLabel())
                assertTrue(reopened.entries.single().getSecretSize() > 0)
            } finally { DesktopUnlockOperation.discard(reopened) }
        } finally { state.lock(); dir.deleteRecursively() }
    }

    @Test
    fun replacementPersistsAndPreservesIdWhileDestroyingOnlyTheOldEntry() {
        val dir = Files.createTempDirectory("compose-edit-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("test-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        try {
            val editor = EntryEditor(controller, state)
            val original = input.build()
            assertTrue(editor.add(original))
            val id = original.getListId()
            val edits = DesktopEntryEditing(state, editor::replace)
            edits.open(id)
            val request = edits.pending!!
            assertTrue(edits.submit(request, edits.initialInput(request)!!.copy(label = "renamed", thumbnailName = "Google"), true))
            val replacement = state.snapshot().single()
            assertEquals(id, replacement.getListId())
            assertEquals("Google", replacement.getThumbnailName())
            assertEquals(0, original.getSecretSize())
            assertTrue(replacement.getSecretSize() > 0)
            assertFalse(edits.submit(request, input, true))
            val reopened = controller.openVault("test-password") as VaultController.Session.Loaded
            val check = DesktopVaultState(reopened.key)
            check.load(reopened.entries)
            try {
                assertEquals("renamed", check.snapshot().single().getLabel())
                assertEquals("Google", check.snapshot().single().getThumbnailName())
            } finally { check.lock() }
        } finally { state.lock(); dir.deleteRecursively() }
    }

    @Test
    fun failedOrThrowingWritesKeepOriginalAndDestroyReplacement() {
        for (throws in listOf(false, true)) {
            val state = state()
            val original = input.copy(thumbnailName = "GitHub").build()
            state.add(original)
            var candidate: Entry? = null
            val edits = DesktopEntryEditing(state) { _, next ->
                candidate = next
                if (throws) error("write failure") else false
            }
            try {
                edits.open(original.getListId())
                val request = edits.pending!!
                assertFalse(edits.submit(request, edits.initialInput(request)!!.copy(label = "changed", thumbnailName = "Google"), true))
                assertEquals(0, candidate!!.getSecretSize())
                assertTrue(state.snapshot().single() === original)
                assertTrue(original.getSecretSize() > 0)
                assertEquals("alice", original.getLabel())
                assertEquals("GitHub", original.getThumbnailName())
                assertEquals(DesktopEntryEditing.Failure.SAVE_FAILED, request.failure)
            } finally { edits.close(); state.lock() }
        }
    }

    @Test
    fun staleRequestsCannotEditAReplacementWithTheSameIdOrALockedSession() {
        val state = state()
        val original = input.build()
        state.add(original)
        var writes = 0
        val edits = DesktopEntryEditing(state) { _, _ -> writes++; false }
        try {
            edits.open(original.getListId())
            val stale = edits.pending!!
            edits.open(original.getListId())
            val current = edits.pending!!
            assertFalse(edits.submit(stale, input, true))
            assertFalse(edits.submit(current, input, false))
            val replacement = input.copy(label = "replacement").build()
            assertTrue(state.replace(original, replacement))
            assertEquals(stale.entryId, replacement.getListId())
            assertFalse(edits.submit(current, input, true))
            assertTrue(edits.initialInput(current) == null)
            edits.open(replacement.getListId())
            val newRequest = edits.pending!!
            edits.dismiss(current)
            assertTrue(edits.pending === newRequest)
            state.lock()
            assertFalse(edits.submit(newRequest, input, true))
            edits.close()
            edits.open(newRequest.entryId)
            assertTrue(edits.pending == null)
            assertEquals(0, writes)
        } finally { edits.close(); state.lock() }
    }

    @Test
    fun editedTypeCannotChangeAndDismissedDialogCannotWrite() {
        val state = state()
        val original = input.build()
        state.add(original)
        var writes = 0
        val edits = DesktopEntryEditing(state) { _, _ -> writes++; false }
        try {
            edits.open(original.getListId())
            val request = edits.pending!!
            assertFalse(edits.submit(request, input.copy(type = Entry.OTPType.HOTP), true))
            assertEquals(DesktopEntryEditing.Failure.INVALID_INPUT, request.failure)
            edits.dismiss(request)
            assertFalse(edits.submit(request, input, true))
            assertEquals(0, writes)
        } finally { edits.close(); state.lock() }
    }
}
