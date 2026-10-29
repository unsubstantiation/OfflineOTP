package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.desktop.control.BackupCoordinator

import java.awt.GridLayout
import java.io.File
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Heavyweight file/password dialogs around the headless [BackupCoordinator]. These classes only
 * collect file choices and passwords; every codec and import decision runs in `:core` or the
 * headless control layer, so the dialogs are compile-verified rather than unit-tested.
 */
object BackupDialogs {

    fun saveEncryptedBackup(parent: JFrame, coordinator: BackupCoordinator) {
        val password = PasswordDialog.prompt(parent, "Backup password") ?: return
        val target = chooseFile(parent, "Save encrypted backup", true,
            Constants.BACKUP_FILENAME_CRYPT, "aes") ?: return
        try {
            target.writeBytes(coordinator.encryptedBackup(password))
            showResult(parent, "Encrypted backup written to ${target.name}")
        } catch (error: Exception) {
            showError(parent, error)
        }
    }

    fun restoreEncryptedBackup(parent: JFrame, coordinator: BackupCoordinator) {
        val source = chooseFile(parent, "Open encrypted backup", false, null, "aes") ?: return
        val password = PasswordDialog.prompt(parent, "Backup password") ?: return
        // The headerless legacy SHA-256 frame is still readable but never newly written; expose it
        // behind an explicit checkbox so the default path stays the current framed format.
        val legacy = JCheckBox("Legacy format (headerless backup from older andOTP versions)")
        val panel = JPanel(GridLayout(0, 1)).apply {
            add(javax.swing.JLabel("Restore replaces all entries in the current vault."))
            add(legacy)
        }
        if (JOptionPane.showConfirmDialog(parent, panel, "Restore options",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        try {
            val count = coordinator.restoreEncrypted(
                password, readBounded(parent, source) ?: return, legacy.isSelected)
            showResult(parent, "Restored $count entr${if (count == 1) "y" else "ies"}")
        } catch (error: Exception) {
            showError(parent, error)
        }
    }

    fun saveOpenPgpBackup(parent: JFrame, coordinator: BackupCoordinator) {
        val certificate = chooseFile(parent, "Select recipient public key", false, null, "asc", "gpg", "pgp")
            ?: return
        val target = chooseFile(parent, "Save OpenPGP backup", true,
            Constants.BACKUP_FILENAME_PGP, "gpg") ?: return
        try {
            val message = coordinator.openPgpBackup(listOf(readBounded(parent, certificate) ?: return))
            target.writeBytes(message)
            showResult(parent, "OpenPGP backup written to ${target.name}")
        } catch (error: Exception) {
            showError(parent, error)
        }
    }

    fun restoreOpenPgp(parent: JFrame, coordinator: BackupCoordinator) {
        val message = chooseFile(parent, "Open OpenPGP backup", false, null, "gpg", "asc", "pgp") ?: return
        val secretKey = chooseFile(parent, "Select secret key", false, null, "asc", "gpg", "pgp") ?: return
        val passphrase = PasswordDialog.prompt(parent, "Secret key passphrase (blank if unprotected)") ?: return
        if (JOptionPane.showConfirmDialog(parent,
                "Restore replaces all entries in the current vault. Continue?", "Restore OpenPGP backup",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return
        try {
            val count = coordinator.restoreOpenPgp(
                readBounded(parent, message) ?: return,
                readBounded(parent, secretKey) ?: return,
                passphrase.takeIf { it.isNotEmpty() }?.toCharArray())
            showResult(parent, "Restored $count entr${if (count == 1) "y" else "ies"}")
        } catch (error: Exception) {
            showError(parent, error)
        }
    }

    fun importQrImage(parent: JFrame, coordinator: BackupCoordinator) {
        // Interactive counterpart of the Android duplicate-issuer confirmation dialog.
        val policy = BackupCoordinator.DuplicateIssuerPolicy { issuer ->
            JOptionPane.showConfirmDialog(
                parent,
                "An entry for \"$issuer\" already exists. Add another account with this issuer?",
                "Duplicate issuer",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            ) == JOptionPane.YES_OPTION
        }
        try {
            while (true) {
                val image = chooseFile(parent, "Select a QR code image", false, null,
                    "png", "jpg", "jpeg", "gif", "bmp", "webp") ?: return
                val bytes = readBounded(parent, image) ?: return
                when (val result = coordinator.importQrImage(bytes, policy)) {
                    is BackupCoordinator.ImportResult.Imported -> {
                        if (result.count == 0) {
                            showResult(parent, "No new entries: every account was already in the vault.")
                        } else {
                            showResult(parent, "Imported ${result.count} entr${if (result.count == 1) "y" else "ies"}")
                        }
                        return
                    }
                    is BackupCoordinator.ImportResult.Invalid -> {
                        showError(parent, "The QR content was invalid: ${result.reason}")
                        return
                    }
                    BackupCoordinator.ImportResult.NotRecognized -> {
                        showError(parent, "No importable QR code was found in that image.")
                        return
                    }
                    BackupCoordinator.ImportResult.DuplicateEntry -> {
                        showError(parent, "That entry is already in the vault.")
                        return
                    }
                    is BackupCoordinator.ImportResult.DuplicateIssuerRejected -> {
                        showResult(parent, "Skipped the entry for \"${result.issuer}\".")
                        return
                    }
                    BackupCoordinator.ImportResult.PersistenceFailed -> {
                        showError(parent, "The vault could not be written; the import was rolled back.")
                        return
                    }
                    is BackupCoordinator.ImportResult.MigrationPart -> {
                        // Keep collecting the remaining QR images; a partial batch is never saved.
                        val continueChoosing = JOptionPane.showConfirmDialog(
                            parent,
                            "Scanned part ${result.received} of ${result.expected}. " +
                                "Select the next QR code image.",
                            "Multi-part export",
                            JOptionPane.OK_CANCEL_OPTION,
                            JOptionPane.INFORMATION_MESSAGE
                        ) == JOptionPane.OK_OPTION
                        if (!continueChoosing) {
                            coordinator.cancelMigrationImport()
                            showResult(parent, "The multi-part import was cancelled; nothing was imported.")
                            return
                        }
                    }
                    is BackupCoordinator.ImportResult.MigrationRejected -> {
                        coordinator.cancelMigrationImport()
                        showError(parent, "The export part was rejected (${result.why.name}).")
                        return
                    }
                }
            }
        } finally {
            // Never leave buffered migration seeds behind if the window flow ends unexpectedly.
            coordinator.cancelMigrationImport()
        }
    }

    /** Rejects files past the shared document bound before their bytes enter memory. */
    private fun readBounded(parent: JFrame, file: File): ByteArray? {
        if (file.length() > BoundedInputReader.MAX_DOCUMENT_BYTES) {
            showError(parent, "The selected file is larger than the ${BoundedInputReader.MAX_DOCUMENT_BYTES}-byte limit.")
            return null
        }
        return file.readBytes()
    }

    private fun chooseFile(
        parent: JFrame, title: String, save: Boolean, suggested: String?,
        vararg extensions: String
    ): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            if (extensions.isNotEmpty()) {
                fileFilter = FileNameExtensionFilter("${extensions.joinToString().uppercase()} files", *extensions)
            }
            if (suggested != null) selectedFile = File(currentDirectory, suggested)
        }
        val choice = if (save) chooser.showSaveDialog(parent) else chooser.showOpenDialog(parent)
        return if (choice == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private fun showResult(parent: JFrame, message: String) {
        JOptionPane.showMessageDialog(parent, message, MainWindow.TITLE, JOptionPane.INFORMATION_MESSAGE)
    }

    private fun showError(parent: JFrame, message: String) {
        JOptionPane.showMessageDialog(parent, message, MainWindow.TITLE, JOptionPane.ERROR_MESSAGE)
    }

    private fun showError(parent: JFrame, error: Exception) {
        showError(parent, error.message ?: error.javaClass.simpleName)
    }
}
