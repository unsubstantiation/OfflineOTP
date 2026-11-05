package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.desktop.control.EmailAccounts

import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

/**
 * Manual "add email account" form, the desktop counterpart of Android's `EmailEntryDialog`.
 *
 * The panel is a plain lightweight component, so it is exercised by the headless desktop test
 * suite; validation stays in [EmailAccounts.create] and `:core`, and [entry] simply propagates the
 * `IllegalArgumentException` that a bad field causes. Password widgets are cleared after the form
 * closes so a cancelled dialog cannot leave credentials in memory.
 */
internal class EmailAccountForm : JPanel(GridBagLayout()) {
    val nameField = JTextField(20).apply { accessibleContext.accessibleName = "Account name" }
    val addressField = JTextField(20).apply { accessibleContext.accessibleName = "Email address" }

    /** FairEmail protocol: 0 = IMAP, 1 = POP. */
    val protocolField = JTextField("0", 4)
    val incomingHostField = JTextField(20)
    val incomingPortField = JTextField("993", 6)
    val incomingEncryptionField = JTextField("0", 4)
    val incomingAuthField = JTextField("1", 4)
    val incomingUserField = JTextField(20)
    val incomingPasswordField = JPasswordField(20)

    val outgoingHostField = JTextField(20)
    val outgoingPortField = JTextField("465", 6)
    val outgoingEncryptionField = JTextField("0", 4)
    val outgoingAuthField = JTextField("1", 4)
    val outgoingUserField = JTextField(20)
    val outgoingPasswordField = JPasswordField(20)

    init {
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        var row = 0
        fun section(title: String) {
            val header = JLabel(title).apply { font = font.deriveFont(font.style or java.awt.Font.BOLD) }
            add(header, constraints(0, row++, width = 2, top = 12))
        }

        fun field(caption: String, input: JTextField, top: Int = 4) {
            val label = JLabel(caption).apply { labelFor = input }
            add(label, constraints(0, row, top = top, fill = GridBagConstraints.NONE, weight = 0.0))
            add(input, constraints(1, row++, top = top, fill = GridBagConstraints.HORIZONTAL, weight = 1.0))
        }

        section("Account")
        field("Account name", nameField)
        field("Email address", addressField)

        section("Incoming mail (IMAP / POP)")
        field("Protocol (0 = IMAP, 1 = POP)", protocolField)
        field("Incoming server host", incomingHostField)
        field("Incoming server port", incomingPortField)
        field("Incoming encryption (0 = SSL, 1 = STARTTLS, 2 = none)", incomingEncryptionField)
        field("Incoming authentication type (1 = password)", incomingAuthField)
        field("Incoming user name", incomingUserField)
        field("Incoming password / app password", incomingPasswordField)

        section("Outgoing mail (SMTP)")
        field("Outgoing server host", outgoingHostField)
        field("Outgoing server port", outgoingPortField)
        field("Outgoing encryption (0 = SSL, 1 = STARTTLS, 2 = none)", outgoingEncryptionField)
        field("Outgoing authentication type (1 = password)", outgoingAuthField)
        field("Outgoing user name", outgoingUserField)
        field("Outgoing password / app password", outgoingPasswordField)

        // Keep the fields at the top of a dialog that is taller than its content needs.
        add(JPanel(), constraints(0, row, width = 2, fill = GridBagConstraints.BOTH, weight = 1.0))
    }

    private fun constraints(column: Int, row: Int, width: Int = 1, top: Int = 0,
                            fill: Int = GridBagConstraints.HORIZONTAL, weight: Double = 0.0): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = column
            gridy = row
            gridwidth = width
            this.fill = fill
            this.weightx = weight
            insets = Insets(top, 0, 0, 8)
            anchor = GridBagConstraints.NORTHWEST
        }

    /** One integer field; a blank or non-numeric value is refused with the field's caption. */
    private fun number(input: JTextField, caption: String): Int =
        input.text.trim().toIntOrNull() ?: throw IllegalArgumentException("$caption must be a number")

    /** Builds the entry from the current values; throws for anything `EmailMaterial` rejects. */
    fun entry(): Entry = EmailAccounts.create(
        name = nameField.text,
        address = addressField.text,
        protocol = number(protocolField, "Protocol"),
        incomingHost = incomingHostField.text,
        incomingPort = number(incomingPortField, "Incoming server port"),
        incomingEncryption = number(incomingEncryptionField, "Incoming encryption"),
        incomingAuth = number(incomingAuthField, "Incoming authentication type"),
        incomingUser = incomingUserField.text,
        incomingPassword = String(incomingPasswordField.password),
        outgoingHost = outgoingHostField.text,
        outgoingPort = number(outgoingPortField, "Outgoing server port"),
        outgoingEncryption = number(outgoingEncryptionField, "Outgoing encryption"),
        outgoingAuth = number(outgoingAuthField, "Outgoing authentication type"),
        outgoingUser = outgoingUserField.text,
        outgoingPassword = String(outgoingPasswordField.password)
    )

    /** Drops the typed passwords once the dialog is gone, keeping the rest for a retry. */
    fun clearSecrets() {
        incomingPasswordField.text = ""
        outgoingPasswordField.text = ""
    }
}
