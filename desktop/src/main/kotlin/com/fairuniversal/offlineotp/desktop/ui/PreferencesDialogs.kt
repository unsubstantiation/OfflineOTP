package com.fairuniversal.offlineotp.desktop.ui

import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.KeyValueSettings
import com.fairuniversal.offlineotp.Utilities.PreferencesExport
import com.fairuniversal.offlineotp.desktop.storage.DesktopPreferencesImportPlan
import com.fairuniversal.offlineotp.desktop.storage.DesktopPreferencesPort

import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane

/**
 * Export/import of the desktop's portable preferences (#648), the peer of Android's
 * **Settings → Export/Import preferences**.
 *
 * The desktop stores no backup password, so there is no "include secrets" question here: the file
 * is settings only, and [DesktopPreferencesPort] says so by never reporting a secret. The vault, its
 * password and its accounts are never part of this file.
 */
internal object PreferencesDialogs {

    private const val TITLE = "Preferences"

    fun export(parent: JFrame, settings: KeyValueSettings) {
        val export = try {
            PreferencesExport.export(DesktopPreferencesPort(settings), includeSecrets = false,
                platform = PreferencesExport.PLATFORM_DESKTOP)
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(parent, "The preferences could not be exported.",
                TITLE, JOptionPane.ERROR_MESSAGE)
            return
        }
        try {
            val picker = JFileChooser().apply {
                dialogTitle = "Export preferences"
                selectedFile = java.io.File("offlineotp-preferences.json")
            }
            if (picker.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return
            if (picker.selectedFile.exists() && JOptionPane.showConfirmDialog(parent,
                    "Replace the existing file?", TITLE, JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
            picker.selectedFile.outputStream().use { it.write(export.bytes) }
            JOptionPane.showMessageDialog(parent,
                "Preferences exported (${export.keysWritten} settings). The file holds no vault password.",
                TITLE, JOptionPane.INFORMATION_MESSAGE)
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(parent, "The preferences could not be exported.",
                TITLE, JOptionPane.ERROR_MESSAGE)
        } finally {
            export.zero()
        }
    }

    /** Returns true when settings were applied, so the caller can refresh the screens it shows. */
    fun import(parent: JFrame, settings: KeyValueSettings): Boolean {
        val picker = JFileChooser().apply { dialogTitle = "Import preferences" }
        if (picker.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return false
        var bytes: ByteArray? = null
        try {
            bytes = picker.selectedFile.inputStream().use {
                BoundedInputReader.read(it, PreferencesExport.MAX_BYTES)
            }
            // Validate before asking: the codec applies nothing unless the whole document is valid.
            val preview = try {
                DesktopPreferencesImportPlan.prepare(bytes)
            } catch (error: IllegalArgumentException) {
                // A file for another client is a different message from an unreadable one.
                val detail = if (error is PreferencesExport.WrongPlatformException)
                    "That file holds ${error.source} preferences; this client can only import ${error.target} ones."
                else "That file is not an OfflineOTP preferences file this version can read."
                JOptionPane.showMessageDialog(parent, detail, TITLE, JOptionPane.ERROR_MESSAGE)
                return false
            }
            if (JOptionPane.showConfirmDialog(parent,
                    "Apply ${preview.applied} settings from this file? Settings it does not mention keep their " +
                        "current values, and the vault, its password and its accounts are not touched.",
                    TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return false
            try {
                preview.applyTo(settings)
            } catch (_: Exception) {
                JOptionPane.showMessageDialog(parent, "The preferences could not be imported.",
                    TITLE, JOptionPane.ERROR_MESSAGE)
                return false
            }
            val skipped = if (preview.unknownCount == 0) "" else
                "\n\n${preview.unknownCount} settings in the file are not known to this version and were skipped."
            JOptionPane.showMessageDialog(parent, "${preview.applied} settings imported.$skipped",
                TITLE, JOptionPane.INFORMATION_MESSAGE)
            return true
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(parent, "The preferences could not be imported.",
                TITLE, JOptionPane.ERROR_MESSAGE)
            return false
        } finally {
            bytes?.fill(0)
        }
    }
}
