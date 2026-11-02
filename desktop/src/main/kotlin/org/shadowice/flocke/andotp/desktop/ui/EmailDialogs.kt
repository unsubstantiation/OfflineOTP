package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.desktop.control.EmailAccounts
import org.shadowice.flocke.andotp.desktop.control.EntryEditor

import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Swing shell around [EmailAccounts]: the manual add form, the read-only connection details view,
 * and FairEmail backup import/export.
 *
 * This class only renders rows and drives file choosers. Every value it shows comes from an
 * encrypted vault entry or from a file the user picked, so values are neutralized before they
 * reach a label ([plainText]) — a host name such as `<html>…` must never be rendered as markup and
 * decode itself into an image fetch.
 */
internal object EmailDialogs {
    private const val ADD_TITLE = "Add email account"
    private const val DETAILS_TITLE = "Email connection details"
    private const val IMPORT_TITLE = "Import FairEmail backup"
    private const val EXPORT_TITLE = "Export FairEmail backup"

    /** Maximum password prompts for one backup file, so a malformed file cannot loop forever. */
    private const val MAX_PASSWORD_ATTEMPTS = 3

    /** Neutralize Swing's HTML label mode for untrusted text. */
    fun plainText(value: String): String = when {
        value.length > 4 && value.regionMatches(0, "<html", 0, 5, ignoreCase = true) ->
            value.replace('<', '\u2039').replace('>', '\u203a')
        else -> value
    }

    /** Manual account entry; returns true when the new entry was persisted. */
    fun addAccount(parent: JFrame, editor: EntryEditor): Boolean {
        val form = EmailAccountForm()
        val body = JScrollPane(form).apply {
            border = BorderFactory.createEmptyBorder()
            preferredSize = java.awt.Dimension(560, 520)
        }
        var created: Entry? = null
        try {
            while (created == null) {
                val choice = JOptionPane.showConfirmDialog(parent, body, ADD_TITLE,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                if (choice != JOptionPane.OK_OPTION) return false
                created = try {
                    form.entry()
                } catch (_: IllegalArgumentException) {
                    JOptionPane.showMessageDialog(parent,
                        "Check the account and connection fields: a host is required, ports must be " +
                            "1–65535, encryption 0–2, the protocol 0–1 and the address must contain @.",
                        ADD_TITLE, JOptionPane.WARNING_MESSAGE)
                    null
                }
            }
            if (editor.add(created)) return true
            created.destroy()
            JOptionPane.showMessageDialog(parent,
                "The email account already exists or could not be saved to the vault.",
                ADD_TITLE, JOptionPane.WARNING_MESSAGE)
            return false
        } finally {
            form.clearSecrets()
        }
    }

    /** Read-only connection details, with passwords masked until the user asks for them. */
    fun showDetails(parent: JFrame, entry: Entry) {
        if (!entry.isEmail()) return
        val masked = EmailAccounts.details(entry, false)
        val revealed = EmailAccounts.details(entry, true)
        if (masked == null || revealed == null || masked.size != revealed.size) {
            JOptionPane.showMessageDialog(parent, "The stored email account could not be read.",
                DETAILS_TITLE, JOptionPane.ERROR_MESSAGE)
            return
        }
        val grid = JPanel(GridBagLayout())
        val secrets = ArrayList<Pair<JLabel, Pair<String, String>>>()
        masked.forEachIndexed { index, field ->
            val caption = JLabel(plainText("${field.label}:")).apply {
                font = font.deriveFont(font.style or java.awt.Font.BOLD)
            }
            val value = JLabel()
            if (field.secret) {
                secrets.add(value to (field.value to revealed[index].value))
                value.text = plainText(field.value)
            } else {
                value.text = plainText(field.value)
            }
            grid.add(caption, constraints(0, index, fill = GridBagConstraints.NONE, weight = 0.0))
            grid.add(value, constraints(1, index, fill = GridBagConstraints.HORIZONTAL, weight = 1.0))
        }
        if (secrets.isNotEmpty()) {
            val toggle = JCheckBox("Show passwords")
            toggle.addActionListener {
                for ((label, values) in secrets) label.text = plainText(if (toggle.isSelected) values.second else values.first)
            }
            grid.add(toggle, constraints(0, masked.size, width = 2, top = 12,
                fill = GridBagConstraints.NONE, weight = 0.0))
        }
        JOptionPane.showMessageDialog(parent, grid, DETAILS_TITLE, JOptionPane.PLAIN_MESSAGE)
    }

    /**
     * Import one FairEmail backup: plain JSON, the gzip agent form, or a password-protected
     * export. Returns true when new entries were written to the vault.
     */
    fun importBackup(parent: JFrame, editor: EntryEditor): Boolean {
        val picker = JFileChooser().apply { dialogTitle = "Select a FairEmail backup" }
        if (picker.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return false
        var bytes: ByteArray? = null
        try {
            bytes = try {
                picker.selectedFile.inputStream().use { BoundedInputReader.read(it, EmailAccounts.MAX_IMPORT_BYTES) }
            } catch (_: Exception) {
                JOptionPane.showMessageDialog(parent,
                    "The backup could not be read, or it is larger than 8 MiB. No items were imported.",
                    IMPORT_TITLE, JOptionPane.ERROR_MESSAGE)
                return false
            }
            if (bytes.isEmpty()) {
                JOptionPane.showMessageDialog(parent,
                    "The selected file is empty. No items were imported.",
                    IMPORT_TITLE, JOptionPane.ERROR_MESSAGE)
                return false
            }
            val imported = try {
                EmailAccounts.importEntries(bytes, null)
            } catch (_: IllegalArgumentException) {
                // FairEmail's normal export can be password protected; a plain/gzip file parses
                // without a prompt, everything else is asked for once and then retried.
                importWithPassword(parent, bytes)
            } ?: return false
            val count = editor.importEntries(imported)
            if (count == null) {
                JOptionPane.showMessageDialog(parent,
                    "The accounts could not be saved to the vault. No items were imported.",
                    IMPORT_TITLE, JOptionPane.ERROR_MESSAGE)
                return false
            }
            JOptionPane.showMessageDialog(parent, if (count == 0)
                "No new email accounts were imported; identical entries were skipped."
            else "Imported $count email account(s).")
            return count > 0
        } finally {
            bytes?.fill(0)
        }
    }

    /** Up to [MAX_PASSWORD_ATTEMPTS] password prompts; null when the user cancels. */
    private fun importWithPassword(parent: JFrame, bytes: ByteArray): List<Entry>? {
        repeat(MAX_PASSWORD_ATTEMPTS) {
            val field = JPasswordField(24).apply {
                accessibleContext.accessibleName = "FairEmail backup password"
            }
            val panel = JPanel().apply {
                add(JLabel("FairEmail backup password:"))
                add(field)
            }
            if (JOptionPane.showConfirmDialog(parent, panel,
                    "$IMPORT_TITLE: password", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
                field.text = ""
                return null
            }
            val password = String(field.password)
            field.text = ""
            if (password.isEmpty()) return null
            try {
                return EmailAccounts.importEntries(bytes, password)
            } catch (_: IllegalArgumentException) {
                JOptionPane.showMessageDialog(parent,
                    "The backup could not be read with that password.",
                    IMPORT_TITLE, JOptionPane.WARNING_MESSAGE)
            }
        }
        JOptionPane.showMessageDialog(parent,
            "Import failed. Check that the file is a FairEmail backup and that its password is correct. " +
                "No items were imported.",
            IMPORT_TITLE, JOptionPane.ERROR_MESSAGE)
        return null
    }

    /**
     * Export the selected email accounts as FairEmail's unencrypted restore file. The user is told
     * twice that the result is plaintext: in the selection dialog and again before the save.
     */
    fun exportBackup(parent: JFrame, entries: List<Entry>): Boolean {
        val emails = EmailAccounts.entries(entries)
        if (emails.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No email accounts to export.",
                EXPORT_TITLE, JOptionPane.INFORMATION_MESSAGE)
            return false
        }
        val boxes = emails.map { JCheckBox(plainText(EmailAccounts.displayLabel(it))) }
        val list = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            boxes.forEach { add(it) }
        }
        val body = JPanel().apply {
            layout = java.awt.BorderLayout(0, 8)
            add(JTextArea("This FairEmail-compatible backup is not password protected and contains " +
                "account passwords. Store it somewhere secure.", 3, 42).apply {
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
        val selected = emails.filterIndexed { index, _ -> boxes[index].isSelected }
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Select at least one account to export.",
                EXPORT_TITLE, JOptionPane.WARNING_MESSAGE)
            return false
        }
        val picker = JFileChooser().apply {
            dialogTitle = "Save FairEmail backup"
            selectedFile = File("fairemail-accounts.json")
        }
        if (picker.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return false
        val target = picker.selectedFile
        if (target.exists() && JOptionPane.showConfirmDialog(parent, "Replace the existing file?",
                EXPORT_TITLE, JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return false
        var bytes: ByteArray? = null
        try {
            bytes = EmailAccounts.exportBackup(selected)
            target.outputStream().use { it.write(bytes) }
            JOptionPane.showMessageDialog(parent,
                "FairEmail backup exported. It contains account passwords in plaintext; store or delete the copy securely.")
            return true
        } catch (_: Exception) {
            // A partly written export must not be left behind looking like a usable backup.
            if (target.exists() && !target.delete()) target.deleteOnExit()
            JOptionPane.showMessageDialog(parent, "The FairEmail backup could not be exported.",
                EXPORT_TITLE, JOptionPane.ERROR_MESSAGE)
            return false
        } finally {
            bytes?.fill(0)
        }
    }

    private fun constraints(column: Int, row: Int, width: Int = 1, top: Int = 0,
                            fill: Int = GridBagConstraints.HORIZONTAL,
                            weight: Double = 0.0): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = column
            gridy = row
            gridwidth = width
            this.fill = fill
            this.weightx = weight
            insets = Insets(top, 0, 0, 12)
            anchor = GridBagConstraints.NORTHWEST
        }
}
