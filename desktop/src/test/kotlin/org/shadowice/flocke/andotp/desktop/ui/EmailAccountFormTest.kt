package org.shadowice.flocke.andotp.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry

/**
 * The add-account form is a lightweight panel, so its field mapping and validation are exercised
 * headlessly; only the surrounding dialog needs a display.
 */
class EmailAccountFormTest {

    private fun filled(): EmailAccountForm = EmailAccountForm().apply {
        nameField.text = "Work"
        addressField.text = "person@example.org"
        protocolField.text = "0"
        incomingHostField.text = "imap.example.org"
        incomingPortField.text = "993"
        incomingEncryptionField.text = "0"
        incomingAuthField.text = "1"
        incomingUserField.text = "person@example.org"
        incomingPasswordField.text = "incoming secret"
        outgoingHostField.text = "smtp.example.org"
        outgoingPortField.text = "465"
        outgoingEncryptionField.text = "0"
        outgoingAuthField.text = "1"
        outgoingUserField.text = "person@example.org"
        outgoingPasswordField.text = "outgoing secret"
    }

    @Test
    fun theFormBuildsAFairEmailCompatibleEntry() {
        val form = filled()
        val entry = form.entry()
        try {
            assertTrue(entry.isEmail())
            assertEquals("Work", entry.getLabel())
            assertEquals("person@example.org", entry.getIssuer())
            val account = entry.getEmailAccount()
            assertEquals("imap.example.org", account.account.getString("host"))
            assertEquals(993, account.account.getInt("port"))
            assertEquals("outgoing secret", account.identity.getString("password"))
        } finally {
            entry.destroy()
            form.clearSecrets()
        }
    }

    @Test
    fun aBlankNameFallsBackToTheAddressLikeAndroid() {
        val form = filled().apply { nameField.text = "  " }
        val entry = form.entry()
        try {
            assertEquals("person@example.org", entry.getLabel())
        } finally {
            entry.destroy()
            form.clearSecrets()
        }
    }

    @Test
    fun nonNumericAndOutOfRangeFieldsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            filled().apply { incomingPortField.text = "" }.entry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            filled().apply { outgoingPortField.text = "not a port" }.entry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            filled().apply { incomingEncryptionField.text = "3" }.entry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            filled().apply { addressField.text = "person" }.entry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            filled().apply { incomingHostField.text = "" }.entry()
        }
    }

    @Test
    fun cancellingTheDialogClearsBothPasswordFields() {
        val form = filled()
        form.clearSecrets()
        assertEquals("", String(form.incomingPasswordField.password))
        assertEquals("", String(form.outgoingPasswordField.password))
        // Everything else survives so the user can correct one field and retry.
        assertEquals("imap.example.org", form.incomingHostField.text)
    }

    @Test
    fun htmlLikeValuesAreNotRenderedAsMarkup() {
        assertEquals("\u2039html\u203a\u2039b\u203a", EmailDialogs.plainText("<html><b>"))
        assertEquals("imap.example.org", EmailDialogs.plainText("imap.example.org"))
        assertEquals("  <html", EmailDialogs.plainText("  <html"))
    }
}
