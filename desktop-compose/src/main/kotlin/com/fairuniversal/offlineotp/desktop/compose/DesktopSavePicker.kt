package com.fairuniversal.offlineotp.desktop.compose

import com.fairuniversal.offlineotp.Utilities.BackupFileNames
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Window
import java.io.File
import java.util.Date

/** Native platform picker, not a Swing widget. The encrypted writer refuses existing files. */
internal object DesktopSavePicker {
    fun openOpenPgpBackup(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Open OpenPGP backup", FileDialog.LOAD)
        return try {
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }

    fun openOpenPgpRecipient(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Select OpenPGP recipient public-key file", FileDialog.LOAD)
        return try {
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }

    fun openOpenPgpKey(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Select OpenPGP secret-key file", FileDialog.LOAD)
        return try {
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }

    fun openLinScpBackup(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Open LinSCP vault, backup or sessions file", FileDialog.LOAD)
        return try {
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }

    fun openLinScpKey(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Select LinSCP sessions.key", FileDialog.LOAD)
        return try {
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }

    fun openEmailBackup(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Open FairEmail backup", FileDialog.LOAD)
        return try {
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }

    fun openPasswordFile(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Open browser password export", FileDialog.LOAD)
        return try {
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }

    fun openQrImage(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Open QR image", FileDialog.LOAD)
        return try {
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }

    fun openEncryptedBackup(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Open encrypted backup", FileDialog.LOAD)
        return try {
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }

    fun linScpExport(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Export encrypted LinSCP vault (choose a new filename)", FileDialog.SAVE)
        return try {
            picker.file = com.fairuniversal.offlineotp.Utilities.LinScpCredentials.exportFilename()
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }
    fun openPgpExport(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Save OpenPGP backup (choose a new filename)", FileDialog.SAVE)
        return try {
            picker.file = BackupFileNames.openPgp(Date())
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }
    fun encryptedBackup(owner: Window): File? {
        val parent = Dialog(owner)
        val picker = FileDialog(parent, "Save encrypted backup (choose a new filename)", FileDialog.SAVE)
        return try {
            picker.file = BackupFileNames.encrypted(Date())
            picker.isVisible = true
            val name = picker.file ?: return null
            File(picker.directory, name)
        } finally { picker.dispose(); parent.dispose() }
    }
}
