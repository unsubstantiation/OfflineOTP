package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

import java.awt.GridLayout
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JTextArea
import javax.swing.JScrollPane

/**
 * Modal add/edit entry form. Heavyweight and compile-only in the sandbox: all validation lives in
 * the core [Entry] constructors, so a rejected form is reported back as an error dialog and this
 * class holds no domain logic of its own.
 *
 * Parity with the Android manual-entry dialog:
 *  - all four [Entry.OTPType] values are offered for new entries (TOTP, HOTP, mOTP, Steam);
 *  - the type is fixed while editing, so a Steam/mOTP entry can never be silently rebuilt as TOTP;
 *  - Steam locks digits/period/algorithm to its contract and mOTP exposes an optional PIN field;
 *  - tags, last-used and usage frequency have no desktop editors and are carried over on edit
 *    rather than reset, matching the Android in-place edit contract.
 */
object EntryDialog {

    fun prompt(parent: JFrame, existing: Entry? = null): Entry? {
        val issuer = JTextField(existing?.getIssuer().orEmpty(), 24)
        val label = JTextField(existing?.getLabel().orEmpty(), 24)
        val sshKeys = existing?.takeIf { it.isSshKey() }?.getSshKeys()
        val secret = JTextArea(if (sshKeys != null) sshKeys.privateKey
            else existing?.takeIf { it.isNote() }?.getNote().orEmpty(), 3, 24)
        val publicKey = JTextArea(sshKeys?.publicKey.orEmpty(), 3, 24)
        val publicKeyLabel = JLabel("SSH public key (optional)")
        val secretLabel = JLabel("Secret (Base32)")
        val type = JComboBox(Entry.OTPType.entries.toTypedArray()).apply {
            selectedItem = existing?.getType() ?: Entry.OTPType.TOTP
            // Android keeps the OTP type fixed on edit; a switch would rebuild the entry and drop
            // type-only fields (the mOTP PIN, the Steam digit contract).
            isEnabled = existing == null
        }
        val digits = JTextField((existing?.getDigits() ?: TokenCalculator.TOTP_DEFAULT_DIGITS).toString(), 8)
        val timingLabel = JLabel("Period / HOTP counter")
        val periodOrCounter = JTextField(
            (if (existing?.getType() == Entry.OTPType.HOTP) existing.getCounter().toString()
             else (existing?.getPeriod() ?: TokenCalculator.TOTP_DEFAULT_PERIOD).toString()), 8)
        val algorithm = JComboBox(
            TokenCalculator.HashAlgorithm.entries.map { it.name }.toTypedArray()).apply {
            selectedItem = (existing?.getAlgorithm() ?: TokenCalculator.HashAlgorithm.SHA1).name
        }
        val pinLabel = JLabel("mOTP PIN (optional)")
        val pin = JTextField(
            existing?.takeIf { it.getType() == Entry.OTPType.MOTP }?.getPin().orEmpty(), 24)

        fun syncTypeFields() {
            val selected = type.selectedItem as Entry.OTPType
            timingLabel.text = when (selected) {
                Entry.OTPType.HOTP -> "HOTP counter"
                Entry.OTPType.MOTP -> "Period (mOTP uses a fixed 10-second step)"
                else -> "Period (seconds)"
            }
            val steam = selected == Entry.OTPType.STEAM
            val motp = selected == Entry.OTPType.MOTP
            val note = selected == Entry.OTPType.NOTE
            val ssh = selected == Entry.OTPType.SSH_KEY
            secretLabel.text = when {
                note -> "Note (up to 200 characters; label is title)"
                ssh -> "SSH private key (optional)"
                else -> "Secret (Base32)"
            }
            publicKey.isEnabled = ssh
            publicKeyLabel.isEnabled = ssh
            // A fresh Steam entry always uses the fixed 5-digit/SHA1 contract.
            if (steam && existing == null) {
                digits.text = TokenCalculator.STEAM_DEFAULT_DIGITS.toString()
                periodOrCounter.text = TokenCalculator.TOTP_DEFAULT_PERIOD.toString()
            }
            digits.isEnabled = !steam && !motp && !note && !ssh
            periodOrCounter.isEnabled = !steam && !motp && !note && !ssh
            algorithm.isEnabled = !steam && !motp && !note && !ssh
            pin.isEnabled = motp
            pinLabel.isEnabled = motp
        }
        type.addActionListener { syncTypeFields() }
        syncTypeFields()

        val panel = JPanel(GridLayout(0, 2, 6, 6)).apply {
            add(JLabel("Issuer")); add(issuer)
            add(JLabel("Label")); add(label)
            add(secretLabel); add(JScrollPane(secret))
            add(publicKeyLabel); add(JScrollPane(publicKey))
            add(JLabel("Type")); add(type)
            add(JLabel("Digits")); add(digits)
            add(timingLabel); add(periodOrCounter)
            add(JLabel("Algorithm")); add(algorithm)
            add(pinLabel); add(pin)
        }

        while (true) {
            val choice = JOptionPane.showConfirmDialog(
                parent, panel, if (existing == null) "Add entry" else "Edit entry",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
            if (choice != JOptionPane.OK_OPTION) {
                secret.text = ""
                publicKey.text = ""
                pin.text = ""
                return null
            }

            val built = try {
                buildEntry(
                    type.selectedItem as Entry.OTPType,
                    issuer.text, label.text,
                    if (type.selectedItem == Entry.OTPType.NOTE || type.selectedItem == Entry.OTPType.SSH_KEY) secret.text
                    else secret.text.ifEmpty { existing?.getSecretEncoded() },
                    digits.text, periodOrCounter.text,
                    TokenCalculator.HashAlgorithm.valueOf(algorithm.selectedItem as String),
                    pin.text, publicKey.text).also { replacement ->
                    // No tag/usage editors on desktop: keep the metadata the form cannot show.
                    if (existing != null) preserveEditMetadata(existing, replacement)
                }
            } catch (error: IllegalArgumentException) {
                JOptionPane.showMessageDialog(parent, error.message ?: "Invalid entry",
                    "Invalid entry", JOptionPane.ERROR_MESSAGE)
                null
            }
            if (built != null) {
                secret.text = ""
                publicKey.text = ""
                pin.text = ""
                return built
            }
        }
    }

    private fun preserveEditMetadata(existing: Entry, replacement: Entry) {
        replacement.setDetailsVisible(existing.areDetailsVisible())
        replacement.setTags(ArrayList(existing.getTags()))
        replacement.setLastUsed(existing.getLastUsed())
        replacement.setUsedFrequency(existing.getUsedFrequency())
    }

    private fun buildEntry(
        type: Entry.OTPType, issuer: String, label: String, secret: String?,
        digitsText: String, timingText: String, algorithm: TokenCalculator.HashAlgorithm,
        pinText: String, publicKeyText: String
    ): Entry {
        val digits = if (type == Entry.OTPType.NOTE || type == Entry.OTPType.SSH_KEY) TokenCalculator.TOTP_DEFAULT_DIGITS
            else digitsText.toIntOrNull() ?: throw IllegalArgumentException("Digits must be a number")
        return when (type) {
            Entry.OTPType.NOTE -> Entry(type, secret ?: "", issuer, label, arrayListOf())
            Entry.OTPType.SSH_KEY -> Entry.sshKey(publicKeyText, secret ?: "", issuer, label, arrayListOf())
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
