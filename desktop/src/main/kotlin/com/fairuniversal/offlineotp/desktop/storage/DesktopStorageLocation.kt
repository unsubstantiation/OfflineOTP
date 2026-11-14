package com.fairuniversal.offlineotp.desktop.storage

import com.fairuniversal.offlineotp.Utilities.Constants
import java.io.File

/** Captured from the actual startup directory, never recomputed from a changed environment. */
class DesktopStorageLocation(directory: File) {
    val dataDirectory: String = directory.absolutePath
    val vaultFilename: String = Constants.FILENAME_DATABASE
    val backupFilename: String = Constants.FILENAME_DATABASE_BACKUP
    val settingsFilename: String = DesktopStorage.SETTINGS_FILE
    val hardwareFilename: String = DesktopStorage.HARDWARE_UNLOCK_FILE
    override fun toString() = "DesktopStorageLocation(redacted)"
}
