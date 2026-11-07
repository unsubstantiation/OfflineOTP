package com.fairuniversal.offlineotp.desktop.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Owner-only permissions for everything the desktop keeps in its data directory.
 *
 * Android gets this from the platform: an app's private storage is `0700` and its files are
 * readable only by its own UID. A desktop JVM has no such default — `File.mkdirs()` and
 * `FileOutputStream` honour the process umask, which is commonly `022`, so a portable install
 * would otherwise leave `secrets.dat`, its backup and the settings readable by every local
 * account (and by anything running as one). The vault is encrypted, but a world-readable
 * ciphertext still hands an attacker an offline password-guessing target and leaks the KDF
 * parameters, so the desktop applies the Android-equivalent modes itself: `0700` for the data
 * directory and `0600` for every file it writes.
 *
 * Staging files are restricted **before** any bytes are written, so a document is never briefly
 * world-readable while it is being written. Existing directories and files are tightened on open,
 * so an install created by an older build is repaired rather than left loose.
 *
 * Tightening only ever *removes* access. Group and other bits are cleared; the owner's own bits are
 * left exactly as they were. A directory the user made read-only stays read-only — the vault must
 * report that it cannot write rather than silently chmod its way past the user's decision (or past
 * a read-only mount). Only paths this code creates itself are granted owner access.
 *
 * Non-POSIX file systems (notably Windows/NTFS) reject POSIX modes. There the JDK owner-only flags
 * are the closest available equivalent; they do not rewrite an inherited ACL, so on those systems
 * the directory's inherited permissions remain the real boundary. That limit is documented rather
 * than hidden behind a silent success.
 */
internal object PrivateFiles {

    private val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
    private val FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")

    private val SHARED_PERMISSIONS = setOf(
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)

    /**
     * Creates [directory] owner-only if it is missing, otherwise clears its group/other bits.
     * Throws if it cannot be created. An existing directory's owner bits are never changed.
     */
    @Throws(IOException::class)
    fun ensureDirectory(directory: File) {
        if (!directory.exists()) {
            try {
                Files.createDirectories(directory.toPath(),
                    PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
            } catch (_: UnsupportedOperationException) {
                if (!directory.mkdirs()) {
                    throw IOException("Cannot create vault directory: $directory")
                }
                grantOwnerOnly(directory, executable = true)
            }
            return
        }
        tighten(directory, executable = true)
    }

    /**
     * Creates an empty owner-only file, failing if it already exists. Used for staging files so
     * the window between creation and the first write never exposes a readable document.
     */
    @Throws(IOException::class)
    fun createPrivate(file: File) {
        try {
            Files.createFile(file.toPath(),
                PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
        } catch (_: UnsupportedOperationException) {
            if (!file.createNewFile() && !file.exists()) {
                throw IOException("Cannot create staging file: ${file.name}")
            }
            grantOwnerOnly(file, executable = false)
        }
    }

    /**
     * Clears an existing file's group/other bits, keeping the owner's. Best effort: a missing file
     * or a file system that refuses the change is left alone rather than failing a vault write.
     */
    fun restrict(file: File) {
        if (!file.exists()) return
        tighten(file, executable = false)
    }

    /** Removes shared access without granting the owner anything it did not already have. */
    private fun tighten(file: File, executable: Boolean) {
        try {
            val current = Files.getPosixFilePermissions(file.toPath())
            val ownerOnly = current - SHARED_PERMISSIONS
            if (ownerOnly != current) Files.setPosixFilePermissions(file.toPath(), ownerOnly)
        } catch (_: UnsupportedOperationException) {
            clearSharedFlags(file, executable)
        } catch (_: IOException) {
            clearSharedFlags(file, executable)
        }
    }

    /**
     * JDK equivalent of [tighten] for file systems without POSIX modes: remember what the owner
     * could do, clear the "everyone" flags, then restore exactly those owner capabilities.
     */
    private fun clearSharedFlags(file: File, executable: Boolean) {
        val readable = file.canRead()
        val writable = file.canWrite()
        val traversable = executable && file.canExecute()
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        if (readable) file.setReadable(true, true)
        if (writable) file.setWritable(true, true)
        if (traversable) file.setExecutable(true, true)
    }

    /** Grants owner-only access to a path this code just created, where no prior intent exists. */
    private fun grantOwnerOnly(file: File, executable: Boolean) {
        try {
            Files.setPosixFilePermissions(file.toPath(),
                if (executable) DIRECTORY_PERMISSIONS else FILE_PERMISSIONS)
        } catch (_: Exception) {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
            if (executable) file.setExecutable(true, true)
        }
    }
}
