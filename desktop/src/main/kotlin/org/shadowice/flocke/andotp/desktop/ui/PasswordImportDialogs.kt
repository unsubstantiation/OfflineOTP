package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.BrowserPasswordCsv
import org.shadowice.flocke.andotp.Utilities.PasswordImport
import org.shadowice.flocke.andotp.desktop.control.EntryEditor
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane

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
        val columns = fields.columns()
        val text = javax.swing.JTextArea(columns.indices.joinToString("\n\n") { "${columns[it]}: ${fields.valueAt(it)}" }, 15, 48)
        text.isEditable = false
        text.lineWrap = true
        text.wrapStyleWord = true
        try { JOptionPane.showMessageDialog(parent, javax.swing.JScrollPane(text), "Password details", JOptionPane.PLAIN_MESSAGE) }
        finally { text.text = "" }
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
