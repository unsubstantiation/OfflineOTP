package org.shadowice.flocke.andotp.desktop.storage

import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.PasswordVault

import java.io.File

/**
 * Wires the portable [PasswordVault] engine to the desktop file system: a per-user data directory
 * holding the same filenames Android uses (`secrets.dat`, `secrets.dat.bck`) plus a properties
 * settings file. No path here is Android-specific, so the assembly is reusable by tests with a
 * temporary directory.
 */
object DesktopStorage {
    const val SETTINGS_FILE = "vault.properties"

    /** Default per-user data directory; overridable (and always overridden in tests). */
    @JvmStatic
    fun defaultDirectory(): File = File(System.getProperty("user.home"), ".offlineotp")

    @JvmStatic
    @JvmOverloads
    fun open(directory: File = defaultDirectory()): PasswordVault {
        if (!directory.exists() && !directory.mkdirs()) {
            throw java.io.IOException("Cannot create vault directory: $directory")
        }
        val vaultFile = NioAtomicFileStore(File(directory, Constants.FILENAME_DATABASE))
        val backupFile = NioAtomicFileStore(File(directory, Constants.FILENAME_DATABASE_BACKUP))
        val settings = DesktopSettings(File(directory, SETTINGS_FILE))
        return PasswordVault(vaultFile, backupFile, settings)
    }
}
