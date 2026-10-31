package org.shadowice.flocke.andotp.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.Constants
import javax.swing.SwingUtilities

/**
 * Android parity for the new-backup password prompt: the password must be typed twice, reach the
 * Android minimum length, and the show toggle must only change the echo characters.
 */
class NewBackupPasswordPanelTest {
    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait { block() }

    private fun panel(): NewBackupPasswordPanel = NewBackupPasswordPanel().also { ForestTheme.install() }

    /** Types into both fields the way a user would, through the document listeners. */
    private fun NewBackupPasswordPanel.type(password: String, confirmation: String) {
        passwordField.text = password
        confirmationField.text = confirmation
    }

    private fun NewBackupPasswordPanel.echoCharacters() =
        passwordField.echoChar to confirmationField.echoChar

    private fun NewBackupPasswordPanel.hintText(): String = hintLabel.text

    @Test fun matchingPasswordLongEnoughAllowsSubmission() = onEdt {
        val panel = panel()
        assertFalse(panel.confirmButtonEnabled)
        panel.type("correct horse", "correct horse")
        assertTrue(panel.confirmButtonEnabled)
        assertNull(panel.problemMessage())
    }

    @Test fun mismatchedConfirmationIsRejected() = onEdt {
        val panel = panel()
        panel.type("correct horse", "correct horsf")
        assertFalse(panel.confirmButtonEnabled)
        assertEquals("The passwords do not match.", panel.problemMessage())
    }

    @Test fun shortPasswordIsRejectedEvenWhenItMatches() = onEdt {
        val panel = panel()
        val short = "a".repeat(Constants.AUTH_MIN_PASSWORD_LENGTH - 1)
        panel.type(short, short)
        assertFalse(panel.confirmButtonEnabled)
        assertEquals("Use at least ${Constants.AUTH_MIN_PASSWORD_LENGTH} characters.", panel.problemMessage())
    }

    @Test fun emptyPasswordAndEmptyConfirmationAreRejected() = onEdt {
        val panel = panel()
        assertFalse(panel.confirmButtonEnabled)
        panel.type("correct horse", "")
        assertFalse(panel.confirmButtonEnabled)
        assertEquals("Repeat the password to confirm it.", panel.problemMessage())
    }

    @Test fun showPasswordOnlyTogglesTheEchoCharacters() = onEdt {
        val panel = panel()
        panel.type("correct horse", "correct horse")
        val hidden = panel.echoCharacters()
        assertNotEquals(0.toChar(), hidden.first)
        panel.setPasswordVisible(true)
        assertTrue(panel.isPasswordVisible())
        assertEquals(0.toChar(), panel.echoCharacters().first)
        assertEquals(0.toChar(), panel.echoCharacters().second)
        // Revealing must not change or re-enter the value.
        assertEquals("correct horse", panel.password())
        panel.setPasswordVisible(false)
        assertEquals(hidden, panel.echoCharacters())
        assertEquals("correct horse", panel.password())
        assertTrue(panel.confirmButtonEnabled)
    }

    @Test fun clearingWipesBothFieldsAndDisablesSubmission() = onEdt {
        val panel = panel()
        panel.type("correct horse", "correct horse")
        panel.clear()
        assertFalse(panel.confirmButtonEnabled)
        assertEquals("", panel.password())
        assertEquals("", panel.confirmation())
    }

    @Test fun hintStaysQuietUntilAConfirmationIsTyped() = onEdt {
        val panel = panel()
        assertEquals(" ", panel.hintText())
        panel.type("correct horse", "")
        assertEquals("Repeat the password to confirm it.", panel.problemMessage())
    }
}
