package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.SshConnection
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JTextArea
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Modal add/edit entry form. Heavyweight and compile-only in the sandbox: all validation lives in
 * the core [Entry] constructors, so a rejected form is reported back as an error dialog and this
 * class holds no domain logic of its own.
 *
 * Parity with the Android manual-entry dialog:
 *  - all OTP types plus secure notes and SSH keys are offered for new entries;
 *  - the type is fixed while editing, so a Steam/mOTP entry can never be silently rebuilt as TOTP;
 *  - Steam locks digits/period/algorithm to its contract and mOTP exposes an optional PIN field;
 *  - tags can be chosen during add/edit; last-used and usage frequency survive edits;
 *  - SSH key entries also store an optional host, port, user name and password.
 */
object EntryDialog {

    fun prompt(parent: JFrame, existing: Entry? = null,
               initialType: Entry.OTPType = Entry.OTPType.TOTP,
               knownTags: List<String> = emptyList()): Entry? {
        // Email entries contain structured account credentials and are managed through the
        // FairEmail-compatible import/export flow, not this OTP-only editor.
        if (existing?.isEmail() == true) return null

        val issuer = JTextField(existing?.getIssuer().orEmpty(), 24)
        val label = JTextField(existing?.getLabel().orEmpty(), 24)
        val sshKeys = existing?.takeIf { it.isSshKey() }?.getSshKeys()
        val secret = JTextArea(if (sshKeys != null) sshKeys.privateKey
            else existing?.takeIf { it.isNote() }?.getNote().orEmpty(), 3, 24)
        val publicKey = JTextArea(sshKeys?.publicKey.orEmpty(), 3, 24)
        // Connection metadata is optional; a blank port field means "not stored" and suggests 22.
        val sshConnection = existing?.takeIf { it.isSshKey() }?.getSshConnection()
        val sshHost = JTextField(sshConnection?.host.orEmpty(), 24)
        val sshPort = JTextField(
            sshConnection?.port?.takeIf { it != 0 }?.let { SshConnection.displayPort(it) }.orEmpty(), 8)
        val sshUsername = JTextField(sshConnection?.username.orEmpty(), 24)
        val sshPassword = JPasswordField(sshConnection?.password.orEmpty(), 24)
        val issuerLabel = JLabel("Issuer")
        val labelLabel = JLabel("Label")
        val publicKeyLabel = JLabel("SSH public key (optional)")
        val sshHostLabel = JLabel("Host name (optional)")
        val sshPortLabel = JLabel("Port (optional, default 22)")
        val sshUsernameLabel = JLabel("User name (optional)")
        val sshPasswordLabel = JLabel("Password (optional)")
        val secretLabel = JLabel("Secret (Base32)")
        val typeLabel = JLabel("Type")
        val type = JComboBox(Entry.OTPType.entries.filter {
            it != Entry.OTPType.PASSWORD && it != Entry.OTPType.PASSWORD_ARCHIVE && it != Entry.OTPType.EMAIL
        }.toTypedArray()).apply {
            selectedItem = existing?.getType() ?: initialType.takeUnless { it == Entry.OTPType.EMAIL }
                ?: Entry.OTPType.TOTP
            // Android keeps the OTP type fixed on edit; a switch would rebuild the entry and drop
            // type-only fields (the mOTP PIN, the Steam digit contract).
            isEnabled = existing == null
        }
        val digitsLabel = JLabel("Digits")
        val digits = JTextField((existing?.getDigits() ?: TokenCalculator.TOTP_DEFAULT_DIGITS).toString(), 8)
        val timingLabel = JLabel("Period / HOTP counter")
        val periodOrCounter = JTextField(
            (if (existing?.getType() == Entry.OTPType.HOTP) existing.getCounter().toString()
             else (existing?.getPeriod() ?: TokenCalculator.TOTP_DEFAULT_PERIOD).toString()), 8)
        val algorithmLabel = JLabel("Algorithm")
        val algorithm = JComboBox(
            TokenCalculator.HashAlgorithm.entries.map { it.name }.toTypedArray()).apply {
            selectedItem = (existing?.getAlgorithm() ?: TokenCalculator.HashAlgorithm.SHA1).name
        }
        var selectedTags = existing?.getTags()?.toList().orEmpty()
        val tagsLabel = JLabel("Tags")
        val tagsSummary = JLabel().apply { putClientProperty("html.disable", true) }
        fun refreshTagsSummary() { tagsSummary.text = if (selectedTags.isEmpty()) "None" else selectedTags.joinToString(", ") }
        refreshTagsSummary()
        val tagsButton = JButton("Choose tags…").apply {
            addActionListener {
                val owner = SwingUtilities.getWindowAncestor(this) ?: parent
                TagDialog.prompt(owner, selectedTags, knownTags)?.let { selectedTags = it; refreshTagsSummary() }
            }
        }
        val tagsControl = JPanel(java.awt.BorderLayout(8, 0)).apply {
            isOpaque = false
            add(tagsSummary, java.awt.BorderLayout.CENTER)
            add(tagsButton, java.awt.BorderLayout.EAST)
        }
        val pinLabel = JLabel("mOTP PIN (optional)")
        val pin = JTextField(
            existing?.takeIf { it.getType() == Entry.OTPType.MOTP }?.getPin().orEmpty(), 24)
        secret.lineWrap = true
        secret.wrapStyleWord = true
        publicKey.lineWrap = true
        val secretScroll = JScrollPane(secret)
        val publicKeyScroll = JScrollPane(publicKey)

        // GridBagLayout keeps text fields compact instead of stretching every row to the height of
        // the multiline key fields. Invisible rows take no space when switching entry types.
        val panel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
            fun row(index: Int, label: JLabel, field: JComponent, labelled: JComponent = field) {
                label.labelFor = labelled
                add(label, GridBagConstraints().apply {
                    gridx = 0; gridy = index; anchor = GridBagConstraints.NORTHWEST
                    insets = Insets(8, 0, 8, 16)
                })
                add(field, GridBagConstraints().apply {
                    gridx = 1; gridy = index; weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST
                    insets = Insets(0, 0, 8, 0)
                })
            }
            row(0, issuerLabel, issuer)
            row(1, labelLabel, label)
            row(2, typeLabel, type)
            row(3, secretLabel, secretScroll, secret)
            row(4, publicKeyLabel, publicKeyScroll, publicKey)
            row(5, sshHostLabel, sshHost)
            row(6, sshPortLabel, sshPort)
            row(7, sshUsernameLabel, sshUsername)
            row(8, sshPasswordLabel, sshPassword)
            row(9, digitsLabel, digits)
            row(10, timingLabel, periodOrCounter)
            row(11, algorithmLabel, algorithm)
            row(12, pinLabel, pin)
            row(13, tagsLabel, tagsControl, tagsButton)
        }
        val formScroll = JScrollPane(panel).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = ForestTheme.SURFACE_ELEVATED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 20
        }

        fun resizeForm() {
            panel.revalidate()
            val preferred = panel.preferredSize
            val availableHeight = (parent.graphicsConfiguration.bounds.height - 180).coerceAtLeast(240)
            formScroll.preferredSize = Dimension(preferred.width.coerceAtLeast(500),
                preferred.height.coerceAtMost(minOf(460, availableHeight)))
            (SwingUtilities.getWindowAncestor(formScroll) as? JDialog)?.let { dialog ->
                dialog.pack()
                dialog.setLocationRelativeTo(parent)
            }
        }

        fun syncTypeFields() {
            val selected = type.selectedItem as Entry.OTPType
            timingLabel.text = when (selected) {
                Entry.OTPType.HOTP -> "HOTP counter"
                Entry.OTPType.MOTP -> "Period (fixed 10-second step)"
                else -> "Period (seconds)"
            }
            val steam = selected == Entry.OTPType.STEAM
            val motp = selected == Entry.OTPType.MOTP
            val note = selected == Entry.OTPType.NOTE
            val ssh = selected == Entry.OTPType.SSH_KEY
            val otp = !note && !ssh
            secretLabel.text = when {
                note -> "Note (up to 200 characters)"
                ssh -> "SSH private key (optional)"
                motp -> "Secret (raw)"
                else -> "Secret (Base32)"
            }
            publicKeyLabel.isVisible = ssh
            publicKeyScroll.isVisible = ssh
            listOf(sshHostLabel, sshHost, sshPortLabel, sshPort,
                sshUsernameLabel, sshUsername, sshPasswordLabel, sshPassword)
                .forEach { it.isVisible = ssh }
            listOf(digitsLabel, digits, timingLabel, periodOrCounter, algorithmLabel, algorithm)
                .forEach { it.isVisible = otp }
            pinLabel.isVisible = motp
            pin.isVisible = motp
            // A fresh Steam entry always uses the fixed 5-digit/SHA1 contract.
            if (steam && existing == null) {
                digits.text = TokenCalculator.STEAM_DEFAULT_DIGITS.toString()
                periodOrCounter.text = TokenCalculator.TOTP_DEFAULT_PERIOD.toString()
            }
            digits.isEnabled = !steam && !motp && otp
            digitsLabel.isEnabled = digits.isEnabled
            periodOrCounter.isEnabled = !steam && !motp && otp
            timingLabel.isEnabled = periodOrCounter.isEnabled
            algorithm.isEnabled = !steam && !motp && otp
            algorithmLabel.isEnabled = algorithm.isEnabled
            resizeForm()
        }
        type.addActionListener { syncTypeFields() }
        syncTypeFields()

        while (true) {
            val title = when {
                existing != null -> "Edit entry"
                initialType == Entry.OTPType.NOTE -> "Add secure note"
                initialType == Entry.OTPType.SSH_KEY -> "Add SSH key"
                else -> "Add entry"
            }
            val choice = JOptionPane.showConfirmDialog(
                parent, formScroll, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
            if (choice != JOptionPane.OK_OPTION) {
                secret.text = ""
                publicKey.text = ""
                sshPassword.text = ""
                pin.text = ""
                return null
            }

            val built = try {
                // Only SSH entries carry connection data, and a blank form stores nothing.
                val connection = if (type.selectedItem == Entry.OTPType.SSH_KEY) {
                    SshConnection.of(sshHost.text, SshConnection.port(sshPort.text),
                        sshUsername.text, String(sshPassword.password))
                } else null
                val replacement = buildEntry(
                    type.selectedItem as Entry.OTPType,
                    issuer.text, label.text,
                    if (type.selectedItem == Entry.OTPType.NOTE || type.selectedItem == Entry.OTPType.SSH_KEY) secret.text
                    else secret.text.ifEmpty { existing?.getSecretEncoded() },
                    digits.text, periodOrCounter.text,
                    TokenCalculator.HashAlgorithm.valueOf(algorithm.selectedItem as String),
                    pin.text, publicKey.text, connection)
                try {
                    if (existing != null) preserveEditMetadata(existing, replacement)
                    replacement.setTags(ArrayList(selectedTags))
                    replacement.validate()
                    replacement
                } catch (error: RuntimeException) {
                    replacement.destroy()
                    throw error
                }
            } catch (error: IllegalArgumentException) {
                JOptionPane.showMessageDialog(parent, error.message ?: "Invalid entry",
                    "Invalid entry", JOptionPane.ERROR_MESSAGE)
                null
            }
            if (built != null) {
                secret.text = ""
                publicKey.text = ""
                sshPassword.text = ""
                pin.text = ""
                return built
            }
        }
    }

    private fun preserveEditMetadata(existing: Entry, replacement: Entry) {
        replacement.setDetailsVisible(existing.areDetailsVisible())
        replacement.setLastUsed(existing.getLastUsed())
        replacement.setUsedFrequency(existing.getUsedFrequency())
    }

    private fun buildEntry(
        type: Entry.OTPType, issuer: String, label: String, secret: String?,
        digitsText: String, timingText: String, algorithm: TokenCalculator.HashAlgorithm,
        pinText: String, publicKeyText: String,
        sshConnection: SshConnection.Details? = null
    ): Entry {
        val digits = if (type == Entry.OTPType.NOTE || type == Entry.OTPType.SSH_KEY) TokenCalculator.TOTP_DEFAULT_DIGITS
            else digitsText.toIntOrNull() ?: throw IllegalArgumentException("Digits must be a number")
        return when (type) {
            Entry.OTPType.PASSWORD, Entry.OTPType.PASSWORD_ARCHIVE -> throw IllegalArgumentException("Use password import")
            Entry.OTPType.EMAIL -> throw IllegalArgumentException("Email entries must be managed through FairEmail import/export")
            Entry.OTPType.NOTE -> Entry(type, secret ?: "", issuer, label, arrayListOf())
            Entry.OTPType.SSH_KEY -> Entry.sshKey(publicKeyText, secret ?: "", issuer, label, arrayListOf(),
                sshConnection)
            Entry.OTPType.HOTP -> {
                val counter = timingText.toLongOrNull()
                    ?: throw IllegalArgumentException("HOTP counter must be a number")
                Entry(type, secret, counter, digits, issuer, label, algorithm, arrayListOf())
            }
            Entry.OTPType.MOTP -> {
                // mOTP seeds are raw strings rather than Base32; the simple constructor stores
                // them verbatim and validate() enforces the seed bounds.
                Entry(type, secret ?: "", issuer, label, arrayListOf()).apply {
                    if (pinText.isNotEmpty()) setPin(pinText)
                }
            }
            Entry.OTPType.TOTP, Entry.OTPType.STEAM -> {
                val period = timingText.toIntOrNull()
                    ?: throw IllegalArgumentException("Period must be a number")
                Entry(type, secret, period, digits, issuer, label, algorithm, arrayListOf())
            }
        }
    }
}
