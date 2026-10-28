package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.desktop.control.BackupCoordinator

import java.io.File
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
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
        try {
            val count = coordinator.restoreEncrypted(password, source.readBytes())
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
            val message = coordinator.openPgpBackup(listOf(certificate.readBytes()))
            target.writeBytes(message)
            showResult(parent, "OpenPGP backup written to ${target.name}")
        } catch (error: Exception) {
            showError(parent, error)
        }
    }

    fun restoreOpenPgp(parent: JFrame, coordinator: BackupCoordinator) {
        val message = chooseFile(parent, "Open OpenPGP backup", false, null, "gpg", "asc", "pgp") ?: return
        val secretKey = chooseFile(parent, "Select secret key", false, null, "asc", "gpg", "pgp") ?: return
        val passphrase = PasswordDialog.prompt(parent, "Secret key passphrase (blank if unprotected)")
        try {
            val count = coordinator.restoreOpenPgp(
                message.readBytes(), secretKey.readBytes(), passphrase?.toCharArray())
            showResult(parent, "Restored $count entr${if (count == 1) "y" else "ies"}")
        } catch (error: Exception) {
            showError(parent, error)
        }
    }

    fun importQrImage(parent: JFrame, coordinator: BackupCoordinator) {
        val image = chooseFile(parent, "Select a QR code image", false, null,
            "png", "jpg", "jpeg", "gif", "bmp", "webp") ?: return
        when (val result = coordinator.importQrImage(image.readBytes())) {
            is BackupCoordinator.ImportResult.Imported ->
                showResult(parent, "Imported ${result.count} entr${if (result.count == 1) "y" else "ies"}")
            is BackupCoordinator.ImportResult.Invalid ->
                showResult(parent, "The QR content was invalid: ${result.reason}")
            BackupCoordinator.ImportResult.NotRecognized ->
                showResult(parent, "No importable QR code was found in that image.")
        }
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

    private fun showError(parent: JFrame, error: Exception) {
        JOptionPane.showMessageDialog(
            parent, error.message ?: error.javaClass.simpleName,
            MainWindow.TITLE, JOptionPane.ERROR_MESSAGE)
    }
}
