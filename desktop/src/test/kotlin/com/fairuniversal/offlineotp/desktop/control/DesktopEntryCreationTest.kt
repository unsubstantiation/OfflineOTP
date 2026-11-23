package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.View.EntryListPresentation
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.vault.EmailEntryInput
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput
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

    @Test fun preparedEmailHandsOffOnceAndPersistsThroughBackgroundTransaction() {
        val dir = Files.createTempDirectory("prepared-email-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key); val worker = ArrayList<Runnable>(); val ui = ArrayList<Runnable>()
        val creation = DesktopEntryCreation(state)
        val transaction = DesktopEntryTransaction(state, controller.detachedSave(java.util.concurrent.Executor { worker.add(it) }, { ui.add(it) }), state::lock)
        var borrowed: Entry? = null
        try {
            creation.open(); val request = creation.pending!!
            assertTrue(creation.prepareEmail(request, email, { true }) { draft ->
                borrowed = draft
                assertFalse(creation.prepareEmail(request, email, { true }) { error("duplicate") })
                creation.close()
                assertTrue(transaction.submitNew(draft, { true }) {})
            })
            assertEquals(0, borrowed!!.getSecretSize()); assertTrue(state.snapshot().isEmpty())
            worker.removeAt(0).run(); ui.removeAt(0).run()
            val reopened = controller.openVault("password") as VaultController.Session.Loaded
            try {
                val account = reopened.entries.single().getEmailAccount()
                assertEquals("incoming", account.account.getString("password"))
                assertEquals("outgoing", account.identity.getString("password"))
            } finally { DesktopUnlockOperation.discard(reopened) }
        } finally { transaction.close(); creation.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun prepareRejectsInvalidDuplicateExpiredAndStaleDraftsAndCleansThrowingHandoff() {
        val state = state(); val creation = DesktopEntryCreation(state)
        try {
            creation.open(); val request = creation.pending!!
            assertFalse(creation.prepare(request, input.copy(secret = "!"), { true }) { error("invalid") })
            assertEquals(DesktopEntryCreation.Failure.INVALID_INPUT, request.failure)
            assertFalse(creation.prepare(request, input, { false }) { error("expired") })
            val existing = input.build(); state.add(existing)
            assertFalse(creation.prepare(request, input, { true }) { error("duplicate") })
            assertEquals(DesktopEntryCreation.Failure.SAVE_FAILED, request.failure)
            creation.open(); val fresh = creation.pending!!
            assertFalse(creation.prepare(request, input, { true }) { error("stale") })
            var borrowed: Entry? = null
            try { creation.prepare(fresh, input.copy(label = "new"), { true }) { borrowed = it; error("handoff") } }
            catch (_: IllegalStateException) {}
            assertEquals(0, borrowed!!.getSecretSize()); assertTrue(creation.pending == null)
            assertTrue(state.snapshot().single() === existing)
        } finally { creation.close(); state.lock() }
    }

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

    @Test
    fun passwordCreationPreservesInitialCategoryClearsFailureAndHandsOffOnce() {
        val dir = Files.createTempDirectory("prepared-password-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val editor = EntryEditor(controller, state)
        val creation = DesktopEntryCreation(state, editor::add)
        try {
            creation.open(EntryListPresentation.Category.PASSWORDS)
            val request = creation.pending!!
            assertEquals(EntryListPresentation.Category.PASSWORDS, request.initialCategory)
            assertFalse(creation.submitPassword(request, PasswordEntryInput(issuer = "GitHub", password = "")))
            assertEquals(DesktopEntryCreation.Failure.INVALID_INPUT, request.failure)
            creation.clearFailure(request)
            assertEquals(null, request.failure)

            var handedOff: Entry? = null
            val valid = PasswordEntryInput(
                issuer = "GitHub",
                url = "https://github.com/login",
                username = "octocat",
                password = "secret-password",
                note = "ssh fallback"
            )
            assertTrue(creation.preparePassword(request, valid, { true }) { draft ->
                handedOff = Entry(draft.toJSON())
            })
            assertEquals(null, creation.pending)
            assertFalse(creation.preparePassword(request, valid, { true }) { })
            val copy = handedOff!!
            try {
                assertTrue(copy.isPassword())
                assertEquals("GitHub", copy.getIssuer())
                assertEquals("octocat", copy.getLabel())
                assertEquals("secret-password", copy.getPasswordFields().value("password"))
                assertEquals("ssh fallback", copy.getPasswordFields().value("note"))
            } finally {
                copy.destroy()
            }
        } finally {
            creation.close()
            state.lock()
            dir.deleteRecursively()
        }
    }
}
