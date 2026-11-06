package com.fairuniversal.offlineotp.desktop.ui

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.LinScpCredentials
import com.fairuniversal.offlineotp.desktop.control.EntryEditor

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Swing import/export dialogs for LinSCP SSH keys and saved sessions:
 * - Import: LinSCP `LSCPVLT2` encrypted vault (`.json.aes`), portable `BackupBundle` (`.json`), or
 *   `sessions.ini` (with optional `sessions.key` selection for `lscp1$` encrypted passwords).
 * - Export: LinSCP `LSCPVLT2` Argon2id + AES-256-GCM encrypted vault (`otp_accounts_*.json.aes`).
 */
internal object LinScpDialogs {
    private const val IMPORT_TITLE = "Import LinSCP SSH & sessions"
    private const val EXPORT_TITLE = "Export to LinSCP encrypted vault"
    private const val MAX_PASSWORD_ATTEMPTS = 3

    enum class ImportedTab { NONE, SSH_KEYS, PASSWORDS }

    fun plainText(value: String): String = when {
        value.length > 4 && value.regionMatches(0, "<html", 0, 5, ignoreCase = true) ->
            value.replace('<', '\u2039').replace('>', '\u203a')
        else -> value
    }

    fun importBackup(parent: JFrame, editor: EntryEditor): ImportedTab {
        val picker = JFileChooser().apply {
            dialogTitle = IMPORT_TITLE
            fileFilter = FileNameExtensionFilter(
                "LinSCP Vault / Backup / Sessions (*.aes, *.json, *.ini)",
                "aes", "json", "ini"
            )
        }
        if (picker.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return ImportedTab.NONE
        val sourceFile = picker.selectedFile
        var bytes: ByteArray? = null
        try {
            bytes = try {
                sourceFile.inputStream().use { BoundedInputReader.read(it, LinScpCredentials.MAX_FILE_BYTES) }
            } catch (_: Exception) {
                JOptionPane.showMessageDialog(
                    parent,
                    "The file could not be read or exceeds 16 MiB. No items were imported.",
                    IMPORT_TITLE,
                    JOptionPane.ERROR_MESSAGE
                )
                return ImportedTab.NONE
            }
            if (bytes.isEmpty()) {
                JOptionPane.showMessageDialog(
                    parent,
                    "The selected file is empty. No items were imported.",
                    IMPORT_TITLE,
                    JOptionPane.ERROR_MESSAGE
                )
                return ImportedTab.NONE
            }

            val imported: ArrayList<Entry> = when {
                LinScpCredentials.isEncryptedVault(bytes) ->
                    importEncryptedVaultWithPassword(parent, bytes) ?: return ImportedTab.NONE
                LinScpCredentials.needsSessionMasterKey(bytes) ->
                    importSessionsWithMasterKey(parent, sourceFile, bytes) ?: return ImportedTab.NONE
                else -> try {
                    LinScpCredentials.importBytes(bytes)
                } catch (_: Exception) {
                    JOptionPane.showMessageDialog(
                        parent,
                        "Import failed. Check that the file is a valid LinSCP vault, backup JSON, or sessions.ini.",
                        IMPORT_TITLE,
                        JOptionPane.ERROR_MESSAGE
                    )
                    return ImportedTab.NONE
                }
            }

            val hasSsh = imported.any { it.isSshKey() }
            val count = editor.importEntries(imported)
            if (count == null) {
                JOptionPane.showMessageDialog(
                    parent,
                    "The credentials could not be saved to the vault. No items were imported.",
                    IMPORT_TITLE,
                    JOptionPane.ERROR_MESSAGE
                )
                return ImportedTab.NONE
            }
            JOptionPane.showMessageDialog(
                parent,
                if (count == 0) "No new LinSCP credentials were imported; identical entries were skipped."
                else "Imported $count LinSCP credential entry(ies)."
            )
            if (count <= 0) return ImportedTab.NONE
            return if (hasSsh) ImportedTab.SSH_KEYS else ImportedTab.PASSWORDS
        } finally {
            bytes?.fill(0)
        }
    }

    private fun importEncryptedVaultWithPassword(parent: JFrame, bytes: ByteArray): ArrayList<Entry>? {
        repeat(MAX_PASSWORD_ATTEMPTS) {
            val field = JPasswordField(24).apply {
                accessibleContext.accessibleName = "LinSCP vault password"
            }
            val panel = JPanel().apply {
                add(JLabel("LinSCP vault password:"))
                add(field)
            }
            if (JOptionPane.showConfirmDialog(
                    parent, panel, "$IMPORT_TITLE: password",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
                ) != JOptionPane.OK_OPTION
            ) {
                field.text = ""
                return null
            }
            val chars = field.password
            field.text = ""
            val password = String(chars)
            chars.fill('\u0000')
            if (password.isEmpty()) return null
            try {
                return LinScpCredentials.importBytes(bytes, password = password)
            } catch (_: Exception) {
                JOptionPane.showMessageDialog(
                    parent,
                    "The LinSCP vault could not be decrypted with that password.",
                    IMPORT_TITLE,
                    JOptionPane.WARNING_MESSAGE
                )
            }
        }
        JOptionPane.showMessageDialog(
            parent,
            "Import failed after $MAX_PASSWORD_ATTEMPTS attempts. No items were imported.",
            IMPORT_TITLE,
            JOptionPane.ERROR_MESSAGE
        )
        return null
    }

    private fun importSessionsWithMasterKey(
        parent: JFrame,
        sourceFile: File,
        bytes: ByteArray
    ): ArrayList<Entry>? {
        val keyPicker = JFileChooser(sourceFile.parentFile).apply {
            dialogTitle = "Select LinSCP sessions.key to decrypt saved passwords"
            val candidate = File(sourceFile.parentFile, "sessions.key")
            if (candidate.isFile) selectedFile = candidate
        }
        if (keyPicker.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null
        var keyBytes: ByteArray? = null
        return try {
            keyBytes = keyPicker.selectedFile.inputStream().use { BoundedInputReader.read(it, 4096) }
            LinScpCredentials.importBytes(bytes, masterKeyBytes = keyBytes)
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(
                parent,
                "Could not decrypt LinSCP session passwords with the selected sessions.key.",
                IMPORT_TITLE,
                JOptionPane.ERROR_MESSAGE
            )
            null
        } finally {
            keyBytes?.fill(0)
        }
    }

    fun exportVault(parent: JFrame, entries: List<Entry>): Boolean {
        val candidates = entries.filter { it.isSshKey() || it.isPassword() }
        if (candidates.isEmpty()) {
            JOptionPane.showMessageDialog(
                parent,
                "No SSH keys or login sessions to export.",
                EXPORT_TITLE,
                JOptionPane.INFORMATION_MESSAGE
            )
            return false
        }

        val boxes = candidates.map { entry ->
            val kind = if (entry.isSshKey()) "SSH" else "Session"
            val host = entry.getIssuer()
            val text = if (host.isNotBlank()) "[$kind] ${entry.getLabel()} ($host)" else "[$kind] ${entry.getLabel()}"
            JCheckBox(plainText(text), true)
        }
        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            boxes.forEach { add(it) }
        }
        val body = JPanel(BorderLayout(0, 8)).apply {
            add(
                JTextArea(
                    "Exports selected SSH keys and login sessions in LinSCP's Argon2id + AES-256-GCM " +
                        "vault format (import in LinSCP via Options \u2192 Credential vault \u2192 Import).",
                    3, 44
                ).apply {
                    isEditable = false
                    isOpaque = false
                    lineWrap = true
                    wrapStyleWord = true
                    accessibleContext.accessibleName = "LinSCP export info"
                },
                BorderLayout.NORTH
            )
            add(JScrollPane(listPanel).apply { preferredSize = Dimension(440, 200) }, BorderLayout.CENTER)
        }
        if (JOptionPane.showConfirmDialog(
                parent, body, EXPORT_TITLE,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
            ) != JOptionPane.OK_OPTION
        ) return false

        val chosen = candidates.filterIndexed { index, _ -> boxes[index].isSelected }
        if (chosen.isEmpty()) {
            JOptionPane.showMessageDialog(
                parent,
                "Select at least one SSH key or session to export.",
                EXPORT_TITLE,
                JOptionPane.WARNING_MESSAGE
            )
            return false
        }

        val exportPassword = promptNewExportPassword(parent) ?: return false
        val picker = JFileChooser().apply {
            dialogTitle = "Save LinSCP encrypted credential vault"
            selectedFile = File(LinScpCredentials.exportFilename())
        }
        if (picker.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return false
        val target = picker.selectedFile
        if (target.exists() && JOptionPane.showConfirmDialog(
                parent, "Replace the existing file?",
                EXPORT_TITLE, JOptionPane.YES_NO_OPTION
            ) != JOptionPane.YES_OPTION
        ) return false

        var encrypted: ByteArray? = null
        try {
            encrypted = LinScpCredentials.exportVault(chosen, exportPassword)
            target.outputStream().use { it.write(encrypted) }
            JOptionPane.showMessageDialog(
                parent,
                "LinSCP encrypted credential vault exported (${chosen.size} item(s))."
            )
            return true
        } catch (_: Exception) {
            if (target.exists() && !target.delete()) target.deleteOnExit()
            JOptionPane.showMessageDialog(
                parent,
                "The LinSCP encrypted credential vault could not be exported.",
                EXPORT_TITLE,
                JOptionPane.ERROR_MESSAGE
            )
            return false
        } finally {
            encrypted?.fill(0)
        }
    }

    private fun promptNewExportPassword(parent: JFrame): String? {
        while (true) {
            val first = JPasswordField(24).apply { accessibleContext.accessibleName = "Vault password" }
            val confirm = JPasswordField(24).apply { accessibleContext.accessibleName = "Confirm vault password" }
            val panel = JPanel(GridLayout(2, 2, 8, 6)).apply {
                add(JLabel("Vault password (min ${Constants.AUTH_MIN_PASSWORD_LENGTH} chars):"))
                add(first)
                add(JLabel("Confirm password:"))
                add(confirm)
            }
            if (JOptionPane.showConfirmDialog(
                    parent, panel, "$EXPORT_TITLE: password",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
                ) != JOptionPane.OK_OPTION
            ) {
                first.text = ""
                confirm.text = ""
                return null
            }
            val firstChars = first.password
            val confirmChars = confirm.password
            first.text = ""
            confirm.text = ""
            val p1 = String(firstChars)
            val p2 = String(confirmChars)
            firstChars.fill('\u0000')
            confirmChars.fill('\u0000')
            if (p1.length < Constants.AUTH_MIN_PASSWORD_LENGTH) {
                JOptionPane.showMessageDialog(
                    parent,
                    "Password must be at least ${Constants.AUTH_MIN_PASSWORD_LENGTH} characters.",
                    EXPORT_TITLE,
                    JOptionPane.WARNING_MESSAGE
                )
                continue
            }
            if (p1 != p2) {
                JOptionPane.showMessageDialog(
                    parent,
                    "Passwords do not match.",
                    EXPORT_TITLE,
                    JOptionPane.WARNING_MESSAGE
                )
                continue
            }
            return p1
        }
    }
}
