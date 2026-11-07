package com.fairuniversal.offlineotp.desktop.ui

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.BrowserPasswordCsv
import com.fairuniversal.offlineotp.Utilities.PasswordCsvExport
import com.fairuniversal.offlineotp.Utilities.PasswordImport
import com.fairuniversal.offlineotp.desktop.control.EntryEditor
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

/** Browser exports and exported archives are plaintext: never log or expose their contents. */
internal object PasswordImportDialogs {
    fun import(parent: JFrame, editor: EntryEditor): Boolean {
        val options = arrayOf("Individual passwords", "Keep original file", "Cancel")
        val choice = JOptionPane.showOptionDialog(parent,
            "Browser exports contain unencrypted passwords. Import individual credentials, or keep the original file unchanged for later export?",
            "Import passwords", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0])
        if (choice !in 0..1) return false
        val picker = JFileChooser().apply { dialogTitle = "Select browser password export" }
        if (picker.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return false
        var bytes: ByteArray? = null
        try {
            bytes = picker.selectedFile.inputStream().use { BoundedInputReader.read(it, BrowserPasswordCsv.MAX_FILE_BYTES) }
            val mode = if (choice == 0) PasswordImport.Mode.INDIVIDUAL else PasswordImport.Mode.ARCHIVE
            val entries = PasswordImport.prepare(bytes, picker.selectedFile.name, mode)
            val count = editor.importEntries(entries)
            if (count == null) throw IllegalStateException()
            JOptionPane.showMessageDialog(parent, "Imported $count item(s). Identical entries were skipped.")
            return true
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(parent, "Import failed. Check the CSV format, vault capacity, and file size (maximum 4 MiB). No items were imported.",
                "Import passwords", JOptionPane.ERROR_MESSAGE)
            return false
        } finally { bytes?.fill(0) }
    }

    fun showDetails(parent: JFrame, entry: Entry) {
        if (!entry.isPassword() || !entry.areDetailsVisible()) return
        val fields = entry.getPasswordFields()
        val body = com.fairuniversal.offlineotp.Utilities.LinScpCredentials.formatPasswordDetails(fields)
        val text = javax.swing.JTextArea(body, 15, 48)
        text.isEditable = false
        text.lineWrap = true
        text.wrapStyleWord = true
        try { JOptionPane.showMessageDialog(parent, javax.swing.JScrollPane(text), "Password details", JOptionPane.PLAIN_MESSAGE) }
        finally { text.text = "" }
    }

    /**
     * Edits the three columns every browser shares. The password box starts empty and keeps the
     * stored value when left blank, so opening the dialog never puts a secret on screen.
     */
    fun edit(parent: JFrame, entry: Entry): Entry? {
        if (!entry.isPassword() || !entry.areDetailsVisible()) return null
        val fields = try { entry.getPasswordFields() } catch (_: Exception) { return null }
        val urlField = JTextField(fields.value(PasswordCsvExport.FIELD_URL), 32)
        val userField = JTextField(fields.value(PasswordCsvExport.FIELD_USERNAME), 32)
        val passwordField = JPasswordField(24).apply { echoChar = '\u2022' }
        passwordField.toolTipText = "Leave blank to keep the stored password"
        val show = JCheckBox("Show password")
        show.addActionListener { passwordField.echoChar = if (show.isSelected) 0.toChar() else '\u2022' }
        val body = JPanel(BorderLayout(0, 8)).apply {
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(labelled("URL", urlField))
                add(labelled("Username", userField))
                add(labelled("Password (blank keeps the stored one)", passwordField))
                add(show)
            }, BorderLayout.NORTH)
            add(javax.swing.JLabel("Other stored columns, such as notes and GUIDs, are kept as they are."),
                BorderLayout.SOUTH)
        }
        try {
            if (JOptionPane.showConfirmDialog(parent, body, "Edit password",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null
            return PasswordCsvExport.withEditedFields(entry, url = urlField.text, username = userField.text,
                password = String(passwordField.password).ifEmpty { null })
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(parent,
                "The password entry could not be updated. A password cannot be empty and every value must fit the entry limits.",
                "Edit password", JOptionPane.ERROR_MESSAGE)
            return null
        } finally {
            urlField.text = ""
            userField.text = ""
            passwordField.text = ""
        }
    }

    private fun labelled(text: String, field: javax.swing.JComponent): JPanel = JPanel(BorderLayout(8, 0)).apply {
        add(javax.swing.JLabel(text), BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
        border = javax.swing.BorderFactory.createEmptyBorder(2, 0, 2, 0)
    }

    /**
     * Writes browser-imported password entries as one CSV in [dialect]'s own header. Entries that
     * did not come from a browser are refused: their column set has no browser form, so exporting
     * them here would drop data the vault is holding.
     */
    fun exportCsv(parent: JFrame, entries: List<Entry>, dialect: PasswordCsvExport.Dialect) {
        val candidates = entries.filter { PasswordCsvExport.support(it) is PasswordCsvExport.Support.Exportable }
        if (candidates.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "No passwords imported from a browser are selected, so there is no browser CSV to write.",
                "Export passwords", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val browser = PasswordCsvExport.browserName(dialect)
        if (JOptionPane.showConfirmDialog(parent,
                "This writes the selected passwords as a plaintext $browser CSV. Anyone with access to the file can read them. Continue?",
                "Export passwords", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return
        val picker = JFileChooser().apply { dialogTitle = "Export passwords"; selectedFile = java.io.File("passwords.csv") }
        if (picker.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return
        if (picker.selectedFile.exists() && JOptionPane.showConfirmDialog(parent,
                "Replace the existing file?", "Export passwords", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        var bytes: ByteArray? = null
        try {
            bytes = PasswordCsvExport.exportCsv(candidates, dialect)
            picker.selectedFile.outputStream().use { it.write(bytes) }
            JOptionPane.showMessageDialog(parent, "Passwords exported. Protect or delete the plaintext copy securely.")
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(parent, "The passwords could not be exported.",
                "Export passwords", JOptionPane.ERROR_MESSAGE)
        } finally { bytes?.fill(0) }
    }

    fun export(parent: JFrame, entry: Entry) {
        if (!entry.isPasswordArchive() || !entry.areDetailsVisible()) return
        if (JOptionPane.showConfirmDialog(parent,
                "This exports the original file in plaintext. Anyone with access to it can read the passwords. Continue?",
                "Export password file", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return
        val picker = JFileChooser().apply { dialogTitle = "Export password file"; selectedFile = java.io.File(entry.getLabel()) }
        if (picker.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return
        if (picker.selectedFile.exists() && JOptionPane.showConfirmDialog(parent,
                "Replace the existing file?", "Export password file", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        val bytes = entry.exportPasswordArchive()
        try {
            picker.selectedFile.outputStream().use { it.write(bytes) }
            JOptionPane.showMessageDialog(parent, "Password file exported. Store or delete the plaintext copy securely.")
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(parent, "The password file could not be exported.", "Export password file", JOptionPane.ERROR_MESSAGE)
        } finally { bytes.fill(0) }
    }
}
