package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Utilities.BrowserPasswordCsv
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.Utilities.PasswordImport
import com.fairuniversal.offlineotp.ui.portable.PortableCard
import com.fairuniversal.offlineotp.ui.portable.PortableCardLayout
import com.fairuniversal.offlineotp.ui.portable.PortableChip
import com.fairuniversal.offlineotp.ui.portable.PortableDetailRow
import com.fairuniversal.offlineotp.ui.portable.PortableEmailAccountDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEmailConnectionDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEmailEditDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEmailField
import com.fairuniversal.offlineotp.ui.portable.PortableEmailIdentityDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEmailPasswordDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEmailSettingsDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEntryDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEntryDragBounds
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds
import com.fairuniversal.offlineotp.ui.portable.portableEntryDragDirection
import com.fairuniversal.offlineotp.ui.portable.PortablePasswordEditDraft
import com.fairuniversal.offlineotp.ui.portable.PortablePasswordImportMode
import com.fairuniversal.offlineotp.ui.portable.PortableRow
import com.fairuniversal.offlineotp.ui.portable.PortableSection
import com.fairuniversal.offlineotp.ui.portable.PortableSurfaceState
import com.fairuniversal.offlineotp.ui.portable.PortableVaultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The toolkit-free values crossing into Kotlin/Native must carry the privacy rules with them. */
class PortablePresentationTest {

    private fun card(revealed: Boolean, code: String = "123456") = PortableCard(
        index = 7,
        title = "Issuer",
        subtitle = "label",
        concealedTitle = "label",
        code = code,
        previousCode = "654321",
        metadata = "TOTP · 12s",
        secondsLeft = 12,
        revealed = revealed,
        canStep = false,
        hasCode = true,
        mark = "Issuer",
        tags = listOf("work")
    )

    @Test
    fun aConcealedCardCarriesNoDisplayableCode() {
        val concealed = card(revealed = false)
        assertEquals("", concealed.visibleCode())
        assertEquals("label", concealed.visibleTitle(hideIssuer = true))
        assertEquals("Issuer", concealed.visibleTitle(hideIssuer = false))

        val revealed = card(revealed = true)
        assertEquals("123456", revealed.visibleCode())
        assertEquals("Issuer", revealed.visibleTitle(hideIssuer = true))
        assertFalse(revealed.toString().contains("123456"))
        assertFalse(revealed.toString().contains("654321"))
    }

    @Test
    fun expiryWarningAndPinPromptFollowCardFlags() {
        assertFalse(card(revealed = true).isExpiring)
        assertTrue(card(revealed = true).copy(secondsLeft = 5).isExpiring)
        assertFalse(card(revealed = true).copy(secondsLeft = 0).isExpiring)
        assertFalse(card(revealed = true).copy(hasCode = false, secondsLeft = 3).isExpiring)
        val motpNoPin = card(revealed = true).copy(hasCode = false, pinRequired = true)
        assertTrue(motpNoPin.pinRequired)
        assertEquals(InputPolicy.MAX_TEXT_CHARS, PortableInputBounds.MAX_TEXT_CHARS)
        assertEquals(InputPolicy.MAX_URI_CHARS, PortableInputBounds.MAX_URI_CHARS)
        assertEquals(InputPolicy.MAX_TAG_CHARS, PortableInputBounds.MAX_TAG_CHARS)
        assertEquals(InputPolicy.MAX_TAGS, PortableInputBounds.MAX_TAGS)
        assertEquals(InputPolicy.MAX_NOTE_CHARACTERS, PortableInputBounds.MAX_NOTE_CHARACTERS)
        assertEquals(BrowserPasswordCsv.MAX_FIELD_CHARS, PortableInputBounds.MAX_PASSWORD_FIELD_CHARS)
        assertEquals(EmailMaterial.MAX_CREDENTIAL_CHARS, PortableInputBounds.MAX_EMAIL_CREDENTIAL_CHARS)
        assertFalse(PortableDetailRow("Password", "top-secret").toString().contains("top-secret"))
        assertFalse(PortablePasswordEditDraft(password = "top-secret").toString().contains("top-secret"))
        assertFalse(PortableEmailAccountDraft(incomingPassword = "top-secret").toString().contains("top-secret"))
        assertFalse(PortableEntryDraft(secret = "JBSWY3DPEHPK3PXP").toString().contains("JBSWY3DPEHPK3PXP"))
        val scrubbed = PortableEntryDraft(
            secret = "JBSWY3DPEHPK3PXP",
            algorithm = "SHA512",
            pin = "1234",
            hiddenLabel = "vault-hint",
            privateKey = "ssh-key",
            sshPassword = "pw",
            clearHiddenLabel = true
        ).withoutSecrets()
        assertEquals("", scrubbed.secret)
        assertEquals("", scrubbed.pin)
        assertEquals("", scrubbed.hiddenLabel)
        assertEquals("", scrubbed.privateKey)
        assertEquals("", scrubbed.sshPassword)
        assertEquals("SHA512", scrubbed.algorithm)
        assertTrue(scrubbed.clearHiddenLabel)
        assertEquals(
            PasswordImport.Mode.values().map { it.name },
            PortablePasswordImportMode.values().map { it.name }
        )
        assertEquals(
            Constants.CardLayouts.values().map { it.name },
            PortableCardLayout.values().map { it.name }
        )
    }

    @Test
    fun theEmptyStateSaysWhetherASearchIsActive() {
        val base = PortableVaultState.EMPTY
        assertTrue(base.emptyMessage.contains("Add an entry"))
        assertTrue(base.copy(query = "git").emptyMessage.contains("search"))
        assertFalse(base.copy(query = "git").emptyMessage.contains("Add an entry"))
    }

    @Test
    fun chipsCarryCountsAndSelectionWithoutRecomputingThem() {
        val selected = PortableChip("work", 3, true)
        assertTrue(selected.selected)
        assertEquals(3, selected.count)
        assertFalse(PortableChip("home", 0, false).selected)
    }

    @Test
    fun aSurfaceExposesOnlyVisibleEditableRows() {
        val surface = PortableSurfaceState(
            key = "editor",
            title = "Edit entry",
            sections = listOf(
                PortableSection("Identity", listOf(
                    PortableRow("issuer", "Issuer", "GitHub", "text"),
                    PortableRow("note", "Preserved", "untouched", "info")
                )),
                PortableSection("Disabled", listOf(
                    PortableRow("busy", "Save", kind = "action", enabled = false)
                ))
            )
        )
        assertEquals(listOf("issuer"), surface.interactiveRows.map { it.key })
    }

    @Test
    fun portableCardCanCopyDefaultsToHasCodeAndSupportsCredentialOverride() {
        val otp = PortableCard(
            index = 0, title = "GitHub", subtitle = "alice", concealedTitle = "alice",
            code = "123 456", previousCode = "", metadata = "TOTP · 20s", secondsLeft = 20,
            revealed = false, canStep = false, hasCode = true, mark = "GitHub", tags = emptyList()
        )
        assertTrue(otp.canCopy)
        val note = otp.copy(hasCode = false, code = "", canCopy = true)
        assertFalse(note.hasCode)
        assertTrue(note.canCopy)
        val draft = PortableEntryDraft(kind = "TOTP", issuer = "Custom", thumbnail = "GitLab")
        assertEquals("GitLab", draft.withoutSecrets().thumbnail)
    }

    @Test
    fun portableEmailDraftsSynchronizeLegacyFlagsScrubSecretsAndRedactToString() {
        assertEquals(
            com.fairuniversal.offlineotp.Utilities.EmailMaterial.MAX_BYTES,
            PortableInputBounds.MAX_EMAIL_BYTES
        )
        val initialFields = listOf(
            PortableEmailField("protocol", "0", PortableEmailField.KIND_NUMBER),
            PortableEmailField("pop", "false", PortableEmailField.KIND_BOOLEAN),
            PortableEmailField("encryption", "0", PortableEmailField.KIND_NUMBER),
            PortableEmailField("starttls", "false", PortableEmailField.KIND_BOOLEAN),
            PortableEmailField("password", "top-secret", PortableEmailField.KIND_TEXT)
        )
        val popUpdated = PortableEmailField.updated(initialFields, 0, "1")
        assertEquals("true", popUpdated[1].value)
        val startTlsUpdated = PortableEmailField.updated(popUpdated, 3, "true")
        assertEquals("1", startTlsUpdated[2].value)

        val editDraft = PortableEmailEditDraft(
            issuer = "Work",
            label = "alice@example.com",
            account = startTlsUpdated,
            identities = listOf(listOf(PortableEmailField("password", "smtp-secret")))
        )
        assertFalse(editDraft.toString().contains("top-secret"))
        val scrubbedEdit = editDraft.withoutSecrets()
        assertEquals("", scrubbedEdit.account.first { it.name == "password" }.value)
        assertEquals("", scrubbedEdit.identities.single().single().value)

        val pwDraft = PortableEmailPasswordDraft(incoming = "in-secret", outgoing = listOf("out-1", "out-2"))
        assertFalse(pwDraft.toString().contains("in-secret"))
        assertEquals(PortableEmailPasswordDraft(incoming = "", outgoing = listOf("", "")), pwDraft.withoutSecrets())

        val settingsDraft = PortableEmailSettingsDraft(
            protocol = 0,
            incoming = PortableEmailConnectionDraft("imap.example.com", "993", 0, "alice", "1"),
            outgoing = listOf(PortableEmailConnectionDraft("smtp.example.com", "465", 0, "alice", "1")),
            identities = listOf(PortableEmailIdentityDraft("Alice", "alice@example.com")),
            accountName = "Work"
        )
        assertFalse(settingsDraft.toString().contains("imap.example.com"))
    }

    @Test
    fun portableEntryDragDirectionMovesOneNeighborStepOnlyPastMidpoint() {
        val visible = listOf(
            PortableEntryDragBounds(id = 10L, top = 0f, height = 60f),
            PortableEntryDragBounds(id = 20L, top = 68f, height = 60f),
            PortableEntryDragBounds(id = 30L, top = 136f, height = 60f)
        )
        val order = listOf(10L, 20L, 30L)
        assertEquals(0, portableEntryDragDirection(20L, pointerY = 40f, order = order, visible = visible))
        assertEquals(-1, portableEntryDragDirection(20L, pointerY = 20f, order = order, visible = visible))
        assertEquals(1, portableEntryDragDirection(20L, pointerY = 175f, order = order, visible = visible))
    }
}
