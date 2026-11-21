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
import com.fairuniversal.offlineotp.ui.portable.PortableEntryDraft
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds
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
}
