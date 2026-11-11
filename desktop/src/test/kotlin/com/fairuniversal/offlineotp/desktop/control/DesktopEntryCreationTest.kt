package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.EmailEntryInput
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEntryCreationTest {
    private val input = ManualEntryInput(secret = "JBSWY3DPEHPK3PXP", issuer = "Example", label = "alice")
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))

    private val email = EmailEntryInput(address = "alice@example.com", incomingHost = "imap.example.com",
        outgoingHost = "smtp.example.com", incomingPassword = "incoming", outgoingPassword = "outgoing")

    @Test fun emailCreationPersistsBothCredentialsAndRejectsReusedRequest() {
        val dir = Files.createTempDirectory("compose-email-add-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val creation = DesktopEntryCreation(state, EntryEditor(controller, state)::add)
        try {
            creation.open(); val request = creation.pending!!
            assertTrue(creation.submitEmail(request, email))
            assertFalse(creation.submitEmail(request, email))
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try {
                val account = reopened.entries.single().getEmailAccount()
                assertEquals("incoming", account.account.getString("password"))
                assertEquals("outgoing", account.identity.getString("password"))
            } finally { DesktopUnlockOperation.discard(reopened) }
        } finally { creation.close(); state.lock(); dir.deleteRecursively() }
    }

    @Test fun emailFailureCleanupAndStaleAuthorityUseTheSameCreationTransaction() {
        val state = state(); var rejected: Entry? = null
        val creation = DesktopEntryCreation(state) { rejected = it; false }
        try {
            creation.open(); val first = creation.pending!!
            assertFalse(creation.submitEmail(first, email.copy(incomingPort = "bad")))
            assertEquals(DesktopEntryCreation.Failure.INVALID_INPUT, first.failure)
            assertTrue(rejected == null)
            assertFalse(creation.submitEmail(first, email))
            assertEquals(DesktopEntryCreation.Failure.SAVE_FAILED, first.failure)
            assertEquals(0, rejected!!.getSecretSize())
            rejected = null
            creation.open(); val second = creation.pending!!
            assertFalse(creation.submitEmail(first, email))
            creation.dismiss(second)
            assertFalse(creation.submitEmail(second, email))
            creation.open(); val third = creation.pending!!
            state.lock()
            assertFalse(creation.submitEmail(third, email))
            assertTrue(rejected == null)
        } finally { creation.close(); state.lock() }
    }

    @Test
    fun successfulAddPersistsAndDuplicateDoesNotReplaceLiveEntry() {
        val dir = Files.createTempDirectory("compose-add-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("test-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        try {
            val creation = DesktopEntryCreation(state, EntryEditor(controller, state)::add)
            creation.open()
            val request = creation.pending!!
            assertTrue(creation.submit(request, input.copy(thumbnailName = "Google")))
            assertTrue(creation.pending == null)
            assertFalse(creation.submit(request, input))
            creation.open()
            assertFalse(creation.submit(creation.pending!!, input))
            assertEquals(1, state.snapshot().size)
            assertTrue(state.snapshot().single().getSecretSize() > 0)
            val reopened = controller.openVault("test-password") as VaultController.Session.Loaded
            val check = DesktopVaultState(reopened.key)
            check.load(reopened.entries)
            try {
                assertEquals("alice", check.snapshot().single().getLabel())
                assertEquals("Google", check.snapshot().single().getThumbnailName())
            } finally { check.lock() }
        } finally { state.lock(); dir.deleteRecursively() }
    }

    @Test
    fun failuresDestroyUnadoptedDraftAndPermitExplicitRetry() {
        val state = state()
        var rejected: Entry? = null
        val creation = DesktopEntryCreation(state) { rejected = it; false }
        try {
            creation.open()
            val request = creation.pending!!
            assertFalse(creation.submit(request, input.copy(secret = "!")))
            assertTrue(rejected == null)
            assertEquals(DesktopEntryCreation.Failure.INVALID_INPUT, request.failure)
            assertFalse(creation.submit(request, input))
            assertEquals(DesktopEntryCreation.Failure.SAVE_FAILED, request.failure)
            assertEquals(0, rejected!!.getSecretSize())
            assertTrue(state.snapshot().isEmpty())
        } finally { creation.close(); state.lock() }
    }

    @Test
    fun dismissReplacementAndLockRejectLateSubmitsBeforeBuildingOrSaving() {
        val state = state()
        var saves = 0
        val creation = DesktopEntryCreation(state) { saves++; false }
        creation.open()
        val first = creation.pending!!
        creation.open()
        val second = creation.pending!!
        assertFalse(creation.submit(first, input))
        creation.dismiss(first)
        assertTrue(creation.pending === second)
        creation.dismiss(second)
        assertFalse(creation.submit(second, input))
        creation.open()
        val third = creation.pending!!
        state.lock() // Even if the UI hasn't disposed its dialog yet.
        assertFalse(creation.submit(third, input))
        creation.close()
        creation.open()
        assertTrue(creation.pending == null)
        assertEquals(0, saves)
    }

    @Test
    fun throwingSaveAlsoDestroysTheCandidate() {
        val state = state()
        var rejected: Entry? = null
        val creation = DesktopEntryCreation(state) { rejected = it; error("save failed") }
        try {
            creation.open()
            assertFalse(creation.submit(creation.pending!!, input))
            assertEquals(0, rejected!!.getSecretSize())
            assertTrue(state.snapshot().isEmpty())
        } finally { creation.close(); state.lock() }
    }
}
