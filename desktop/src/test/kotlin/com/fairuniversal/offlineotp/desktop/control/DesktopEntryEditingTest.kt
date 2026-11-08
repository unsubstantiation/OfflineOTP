package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
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
