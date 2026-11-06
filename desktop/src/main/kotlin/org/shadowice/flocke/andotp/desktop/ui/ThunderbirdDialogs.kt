package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.ThunderbirdCsv
import org.shadowice.flocke.andotp.Utilities.ThunderbirdImport
import org.shadowice.flocke.andotp.desktop.control.EntryEditor

import java.io.File
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Swing shell for Thunderbird email credential import/export.
 *
 * Thunderbird stores email logins (hostname — mailbox://, imap://, smtp:// — plus username
 * and password) in its password manager, exportable as the same CSV Firefox uses (url,
 * username, password …) or the hostname-based variant from raw logins.json. OfflineOTP
 * keeps imported logins as PASSWORD entries so they round-trip through encrypted backups,
 * tag/search/sort, and the Passwords-tab details view without needing a separate vault kind.
 */
internal object ThunderbirdDialogs {
    private const val IMPORT_TITLE = "Import Thunderbird credentials"
    private const val EXPORT_TITLE = "Export Thunderbird credentials"

    fun importBackup(parent: JFrame, editor: EntryEditor): Boolean {
        val picker = JFileChooser().apply { dialogTitle = "Select Thunderbird CSV export" }
        if (picker.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return false
        var bytes: ByteArray? = null
        try {
            bytes = try {
                picker.selectedFile.inputStream().use { BoundedInputReader.read(it, ThunderbirdCsv.MAX_FILE_BYTES) }
            } catch (_: Exception) {
                JOptionPane.showMessageDialog(parent,
                    "The file could not be read, or it is larger than 4 MiB. No items were imported.",
                    IMPORT_TITLE, JOptionPane.ERROR_MESSAGE)
                return false
            }
            if (bytes.isEmpty()) {
                JOptionPane.showMessageDialog(parent,
                    "The selected file is empty. No items were imported.",
                    IMPORT_TITLE, JOptionPane.ERROR_MESSAGE)
                return false
            }
            val entries = try {
                ThunderbirdImport.prepare(bytes)
            } catch (_: Exception) {
                JOptionPane.showMessageDialog(parent,
                    "Import failed. Check the CSV format (hostname/url, username, password), vault capacity and 4 MiB limit. No items were imported.",
                    IMPORT_TITLE, JOptionPane.ERROR_MESSAGE)
                return false
            }
            val count = editor.importEntries(entries)
            if (count == null) {
                JOptionPane.showMessageDialog(parent,
                    "The credentials could not be saved to the vault. No items were imported.",
                    IMPORT_TITLE, JOptionPane.ERROR_MESSAGE)
                return false
            }
            JOptionPane.showMessageDialog(parent, if (count == 0)
                "No new Thunderbird credentials were imported; identical entries were skipped."
            else "Imported $count Thunderbird credential(s).")
            return count > 0
        } finally {
            bytes?.fill(0)
        }
    }

    fun exportBackup(parent: JFrame, entries: List<Entry>): Boolean {
        val passwords = entries.filter { it.isPassword() }
        if (passwords.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No credentials to export.",
                EXPORT_TITLE, JOptionPane.INFORMATION_MESSAGE)
            return false
        }
        val boxes = passwords.map { JCheckBox(plainText("${it.getLabel()} (${it.getIssuer()})")) }
        val list = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            boxes.forEach { add(it) }
        }
        val body = JPanel().apply {
            layout = java.awt.BorderLayout(0, 8)
            add(JTextArea("This Thunderbird-compatible CSV is not encrypted and contains passwords. Store it securely.", 3, 42).apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                accessibleContext.accessibleName = "Export warning"
            }, java.awt.BorderLayout.NORTH)
            add(JScrollPane(list).apply { preferredSize = java.awt.Dimension(420, 180) },
                java.awt.BorderLayout.CENTER)
        }
        if (JOptionPane.showConfirmDialog(parent, body, EXPORT_TITLE,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return false
        val selected = passwords.filterIndexed { index, _ -> boxes[index].isSelected }
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Select at least one credential to export.",
                EXPORT_TITLE, JOptionPane.WARNING_MESSAGE)
            return false
        }
        val picker = JFileChooser().apply {
            dialogTitle = "Save Thunderbird CSV"
            selectedFile = File("thunderbird-credentials.csv")
        }
        if (picker.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return false
        val target = picker.selectedFile
        if (target.exists() && JOptionPane.showConfirmDialog(parent, "Replace the existing file?",
                EXPORT_TITLE, JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return false
        var bytes: ByteArray? = null
        try {
            bytes = ThunderbirdImport.exportCsv(selected)
            target.outputStream().use { it.write(bytes) }
            JOptionPane.showMessageDialog(parent,
                "Thunderbird credentials exported. The CSV is plaintext; store or delete the copy securely.")
            return true
        } catch (_: Exception) {
            if (target.exists() && !target.delete()) target.deleteOnExit()
            JOptionPane.showMessageDialog(parent, "The Thunderbird credentials could not be exported.",
                EXPORT_TITLE, JOptionPane.ERROR_MESSAGE)
            return false
        } finally {
            bytes?.fill(0)
        }
    }

    fun plainText(value: String): String = when {
        value.length > 4 && value.regionMatches(0, "<html", 0, 5, ignoreCase = true) ->
            value.replace('<', '\u2039').replace('>', '\u203a')
        else -> value
    }
}
