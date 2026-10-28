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

/**
 * Modal add/edit entry form. Heavyweight and compile-only in the sandbox: all validation lives in
 * the core [Entry] constructors, so a rejected form is reported back as an error dialog and this
 * class holds no domain logic of its own.
 */
object EntryDialog {

    fun prompt(parent: JFrame, existing: Entry? = null): Entry? {
        val issuer = JTextField(existing?.getIssuer().orEmpty(), 24)
        val label = JTextField(existing?.getLabel().orEmpty(), 24)
        val secret = JTextField("", 24)
        val type = JComboBox(arrayOf(Entry.OTPType.TOTP.name, Entry.OTPType.HOTP.name)).apply {
            selectedItem = (existing?.getType() ?: Entry.OTPType.TOTP).name
        }
        val digits = JTextField((existing?.getDigits() ?: 6).toString(), 8)
        val periodOrCounter = JTextField(
            (if (existing?.getType() == Entry.OTPType.HOTP) existing.getCounter().toString()
             else (existing?.getPeriod() ?: 30).toString()), 8)
        val algorithm = JComboBox(
            TokenCalculator.HashAlgorithm.entries.map { it.name }.toTypedArray()).apply {
            selectedItem = (existing?.getAlgorithm() ?: TokenCalculator.HashAlgorithm.SHA1).name
        }

        val panel = JPanel(GridLayout(0, 2, 6, 6)).apply {
            add(JLabel("Issuer")); add(issuer)
            add(JLabel("Label")); add(label)
            add(JLabel("Secret (Base32)")); add(secret)
            add(JLabel("Type")); add(type)
            add(JLabel("Digits")); add(digits)
            add(JLabel("Period / HOTP counter")); add(periodOrCounter)
            add(JLabel("Algorithm")); add(algorithm)
        }

        while (true) {
            val choice = JOptionPane.showConfirmDialog(
                parent, panel, if (existing == null) "Add entry" else "Edit entry",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
            if (choice != JOptionPane.OK_OPTION) return null

            val built = try {
                buildEntry(
                    Entry.OTPType.valueOf(type.selectedItem as String),
                    issuer.text, label.text,
                    secret.text.ifEmpty { existing?.getSecretEncoded() },
                    digits.text, periodOrCounter.text,
                    TokenCalculator.HashAlgorithm.valueOf(algorithm.selectedItem as String))
            } catch (error: IllegalArgumentException) {
                JOptionPane.showMessageDialog(parent, error.message ?: "Invalid entry",
                    "Invalid entry", JOptionPane.ERROR_MESSAGE)
                null
            }
            if (built != null) return built
        }
    }

    private fun buildEntry(
        type: Entry.OTPType, issuer: String, label: String, secret: String?,
        digitsText: String, timingText: String, algorithm: TokenCalculator.HashAlgorithm
    ): Entry {
        val digits = digitsText.toIntOrNull()
            ?: throw IllegalArgumentException("Digits must be a number")
        return if (type == Entry.OTPType.HOTP) {
            val counter = timingText.toLongOrNull()
                ?: throw IllegalArgumentException("HOTP counter must be a number")
            Entry(type, secret, counter, digits, issuer, label, algorithm, arrayListOf())
        } else {
            val period = timingText.toIntOrNull()
                ?: throw IllegalArgumentException("Period must be a number")
            Entry(type, secret, period, digits, issuer, label, algorithm, arrayListOf())
        }
    }
}
