package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Utilities.Constants

import java.awt.GridLayout
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The rule Android applies when a *new* backup password is chosen
 * (`PasswordEntryDialog.Mode.UPDATE`): it must be non-empty, reach
 * [Constants.AUTH_MIN_PASSWORD_LENGTH] and match its confirmation. Restoring an existing backup is
 * deliberately exempt — that file was written with whatever password it was created with.
 */
internal object NewBackupPassword {
    fun problem(password: String, confirmation: String): String? = when {
        password.isEmpty() -> "Enter a password for the backup."
        password.length < Constants.AUTH_MIN_PASSWORD_LENGTH ->
            "Use at least ${Constants.AUTH_MIN_PASSWORD_LENGTH} characters."
        confirmation.isEmpty() -> "Repeat the password to confirm it."
        password != confirmation -> "The passwords do not match."
        else -> null
    }

    fun canSubmit(password: String, confirmation: String): Boolean =
        problem(password, confirmation) == null
}

/**
 * Password + confirmation fields with a **Show password** toggle, laid out like the Android
 * backup-password dialog. Kept as a plain [JPanel] (no modal parent) so the validation, the echo
 * toggle and the mismatch hint can be exercised headlessly by the desktop test gate.
 */
internal class NewBackupPasswordPanel : JPanel(GridLayout(0, 1, 0, 6)) {
    // The widgets stay visible to the module so the desktop tests can drive them like a user.
    internal val passwordField = JPasswordField(20)
    internal val confirmationField = JPasswordField(20)
    internal val showToggle = JCheckBox("Show password")
    internal val hintLabel = JLabel(" ")

    /** Echo characters from the look and feel, restored when the toggle is switched back off. */
    private val hiddenEchoChar = passwordField.echoChar
    private val hiddenConfirmEchoChar = confirmationField.echoChar

    /** True once the two fields agree and satisfy the length rule. */
    var confirmButtonEnabled: Boolean = false
        private set

    init {
        add(JLabel("Password:"))
        add(passwordField)
        add(JLabel("Confirm password:"))
        add(confirmationField)
        add(showToggle)
        hintLabel.foreground = ForestTheme.WARNING
        add(hintLabel)

        val refresh = object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = updateState()
            override fun removeUpdate(event: DocumentEvent) = updateState()
            override fun changedUpdate(event: DocumentEvent) = updateState()
        }
        passwordField.document.addDocumentListener(refresh)
        confirmationField.document.addDocumentListener(refresh)
        showToggle.addActionListener { setPasswordVisible(showToggle.isSelected) }
        updateState()
    }

    /** The typed password. Callers must not keep it longer than the backup write needs it. */
    fun password(): String = String(passwordField.password)

    fun confirmation(): String = String(confirmationField.password)

    /** Reveals or re-hides both fields; it never changes the values. */
    fun setPasswordVisible(visible: Boolean) {
        if (showToggle.isSelected != visible) showToggle.isSelected = visible
        passwordField.echoChar = if (visible) 0.toChar() else hiddenEchoChar
        confirmationField.echoChar = if (visible) 0.toChar() else hiddenConfirmEchoChar
    }

    fun isPasswordVisible(): Boolean = showToggle.isSelected

    /** The explanation shown under the fields, or null while nothing is wrong yet. */
    fun problemMessage(): String? = NewBackupPassword.problem(password(), confirmation())

    /** Wipes both fields once the dialog produced its result. */
    fun clear() {
        passwordField.text = ""
        confirmationField.text = ""
        updateState()
    }

    private fun updateState() {
        val problem = problemMessage()
        confirmButtonEnabled = problem == null
        // Stay quiet until the user has typed something in both fields; a fresh dialog should not
        // open already complaining.
        hintLabel.text = if (confirmation().isEmpty()) " " else problem ?: " "
    }

    companion object {
        /** Modal prompt returning the confirmed password, or null when the user cancels. */
        fun ask(parent: JFrame, title: String): String? {
            val panel = NewBackupPasswordPanel()
            try {
                while (true) {
                    val choice = JOptionPane.showConfirmDialog(
                        parent, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    if (choice != JOptionPane.OK_OPTION) return null
                    if (panel.confirmButtonEnabled) return panel.password()
                    // Keep the typed text on screen; the hint already explains what is missing.
                }
            } finally {
                panel.clear()
            }
        }
    }
}

/**
 * Modal password prompts; returns the typed password or null when cancelled. Both prompts keep the
 * text in a `JPasswordField` (never a `JTextField`) so the characters cannot be read back from the
 * widget by an accessibility or clipboard client while they are hidden.
 */
object PasswordDialog {
    /** Single-field prompt for an existing secret (restore password, key passphrase). */
    fun prompt(parent: JFrame, title: String): String? {
        val field = JPasswordField(20)
        val panel = JPanel(GridLayout(0, 1, 0, 6)).apply {
            add(JLabel("Password:"))
            add(field)
        }
        val choice = JOptionPane.showConfirmDialog(
            parent, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        return if (choice == JOptionPane.OK_OPTION) String(field.password) else null
    }

    /** New-backup prompt: password + confirmation + show-password toggle (Android parity). */
    fun promptNewBackupPassword(parent: JFrame, title: String = "Backup password"): String? =
        NewBackupPasswordPanel.ask(parent, title)
}
