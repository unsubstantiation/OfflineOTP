package com.fairuniversal.offlineotp.desktop.ui

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.desktop.qr.QrCodeImage

import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The desktop counterpart of Android's **Show QR code**: renders the selected entry's `otpauth://`
 * URI as a scannable code so it can be moved to another device without a backup file.
 *
 * Only OTP entries have a QR form. Notes, SSH keys, password entries and email accounts are refused
 * by [QrCodeImage.payloadFor] before any dialog is built, matching the Android popup, which hides
 * the action for those kinds. The image is generated in memory and never written to disk.
 */
internal object QrCodeDialog {

    const val TITLE = "QR code"

    /** Sentinel returned when the entry has no QR form, so callers can report it instead. */
    const val UNSUPPORTED = "This entry cannot be shown as a QR code."

    /** Shows the code for [entry]; returns false when the kind cannot be encoded or rendering fails. */
    fun show(parent: JFrame, entry: Entry): Boolean {
        val payload = QrCodeImage.payloadFor(entry) ?: return false
        val image = try {
            QrCodeImage.render(payload)
        } catch (_: RuntimeException) {
            // An unencodable payload (over-long labels, for example) must not end in a stack trace.
            return false
        }

        // The issuer and label are user text; neutralize markup like the email details dialog does.
        val heading = JLabel(EmailDialogs.plainText(entryTitle(entry))).apply {
            horizontalAlignment = SwingConstants.CENTER
        }
        val code = JLabel(ImageIcon(image)).apply {
            horizontalAlignment = SwingConstants.CENTER
            accessibleContext.accessibleName = "QR code for ${entryTitle(entry)}"
        }
        val body = JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 4, 12)
            add(heading, BorderLayout.NORTH)
            add(code, BorderLayout.CENTER)
        }
        JOptionPane.showMessageDialog(parent, body, TITLE, JOptionPane.PLAIN_MESSAGE)
        return true
    }

    /** Issuer and label, without the concealed fields; used for the heading and the accessible name. */
    fun entryTitle(entry: Entry): String = when {
        entry.getIssuer().isBlank() -> entry.getLabel()
        entry.getLabel().isBlank() -> entry.getIssuer()
        else -> "${entry.getIssuer()} – ${entry.getLabel()}"
    }
}
