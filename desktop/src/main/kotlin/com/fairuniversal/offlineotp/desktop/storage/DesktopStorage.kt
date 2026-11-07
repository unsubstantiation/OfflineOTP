package com.fairuniversal.offlineotp.desktop.storage

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.PasswordVault

import java.io.File

/**
 * Wires the portable [PasswordVault] engine to the desktop file system: a data directory inside the
 * program's own folder, holding the same filenames Android uses (`secrets.dat`, `secrets.dat.bck`)
 * plus a properties settings file. Nothing is written to the user's profile, so a Debian or Windows
 * build is portable: copy the folder (to a USB stick, another machine, an archive) and the vault,
 * its settings and the hardware-unlock record travel with it.
 *
 * The program folder is resolved in this order:
 *  1. `-Dofflineotp.home=<folder>` or the `OFFLINEOTP_HOME` environment variable, for a read-only or
 *     shared installation that must keep its data elsewhere;
 *  2. `jpackage.app-path`, which the jpackage launcher sets to `<app>/bin/<launcher>`, so the folder
 *     that holds `bin/` and `lib/` is the program folder;
 *  3. the directory of the running jar (a plain `java -jar` launch);
 *  4. the current working directory, for exploded classes (`gradle run`, an IDE).
 *
 * Data goes in a [DATA_DIRECTORY] subfolder of that program folder so the vault files never mix with
 * the launcher, libraries and icons.
 */
object DesktopStorage {
    const val SETTINGS_FILE = "vault.properties"

    /** Record written by [DesktopHardwareUnlockStore]; tightened here alongside the vault files. */
    val HARDWARE_UNLOCK_FILE = DesktopHardwareUnlockStore.RECORD_FILE

    /** Subfolder of the program folder that holds the vault, its backup and the settings. */
    const val DATA_DIRECTORY = "data"

    /** System property naming the program folder explicitly. */
    const val HOME_PROPERTY = "offlineotp.home"

    /** Environment variable naming the program folder explicitly. */
    const val HOME_ENVIRONMENT = "OFFLINEOTP_HOME"

    /** Set by jpackage launchers to `<app>/bin/<launcher>`. */
    const val JPACKAGE_LAUNCHER_PROPERTY = "jpackage.app-path"

    /**
     * The folder the program was launched from. Never the user's home directory: an unwritable
     * program folder is reported instead of silently falling back to a per-user location.
     */
    @JvmStatic
    fun programDirectory(): File = PortableLocations.resolve(
        configured = System.getProperty(HOME_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: System.getenv(HOME_ENVIRONMENT),
        launcher = System.getProperty(JPACKAGE_LAUNCHER_PROPERTY),
        codeSource = codeSource(),
        workingDirectory = System.getProperty("user.dir") ?: "."
    )

    /** Default vault directory: the program folder's [DATA_DIRECTORY] subfolder. */
    @JvmStatic
    fun defaultDirectory(): File = File(programDirectory(), DATA_DIRECTORY)

    @JvmStatic
    @JvmOverloads
    fun open(directory: File = defaultDirectory()): PasswordVault {
        // Android's app-private storage is owner-only by platform default; the desktop has to ask
        // for it. This also repairs a data directory created by an older, umask-default build.
        PrivateFiles.ensureDirectory(directory)
        if (!directory.isDirectory || !directory.canWrite()) {
            throw java.io.IOException(
                "The vault directory is not writable: $directory. Move the OfflineOTP folder " +
                    "somewhere writable, or pass -D$HOME_PROPERTY=<folder>.")
        }
        // Files an older build may already have written with the process umask.
        for (name in arrayOf(Constants.FILENAME_DATABASE, Constants.FILENAME_DATABASE_BACKUP,
                SETTINGS_FILE, HARDWARE_UNLOCK_FILE)) {
            PrivateFiles.restrict(File(directory, name))
        }
        val vaultFile = NioAtomicFileStore(File(directory, Constants.FILENAME_DATABASE))
        val backupFile = NioAtomicFileStore(File(directory, Constants.FILENAME_DATABASE_BACKUP))
        val settings = DesktopSettings(File(directory, SETTINGS_FILE))
        return PasswordVault(vaultFile, backupFile, settings)
    }

    /** Where this class was loaded from: a jar file, or a classes directory during development. */
    private fun codeSource(): File? = try {
        DesktopStorage::class.java.protectionDomain?.codeSource?.location?.let { File(it.toURI()) }
    } catch (_: Exception) {
        // A security manager or an unparseable URL means "unknown"; the caller falls back.
        null
    }
}

/**
 * Pure resolution of the program folder, kept separate from the environment reads so the rules are
 * unit-tested without touching system properties.
 */
internal object PortableLocations {

    /**
     * Returns the program folder. [configured] wins when set; [launcher] is jpackage's
     * `jpackage.app-path`; [codeSource] is the running jar or classes directory; and
     * [workingDirectory] is the last resort.
     */
    fun resolve(configured: String?, launcher: String?, codeSource: File?, workingDirectory: String): File {
        val explicit = configured?.trim()
        if (!explicit.isNullOrEmpty()) return File(explicit).absoluteFile

        val launcherFile = launcher?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }
        if (launcherFile != null) {
            val parent = launcherFile.absoluteFile.parentFile
            if (parent != null) {
                // jpackage on Linux puts the launcher in <app>/bin/<launcher>; on macOS in
                // <app>.app/Contents/MacOS/<launcher>; on Windows in <app>/<launcher>.exe.
                if (parent.name == "bin" && parent.parentFile != null) return parent.parentFile
                if (parent.name == "MacOS" && parent.parentFile?.name == "Contents" &&
                    parent.parentFile?.parentFile != null) {
                    return parent.parentFile.parentFile
                }
                return parent
            }
        }

        // A jar launch: the jar sits in (or one level below) the program folder, depending on how
        // the build was packaged. An exploded classes directory (gradle run, an IDE) is not a
        // program folder, so it falls through to the working directory.
        if (codeSource != null && codeSource.name.endsWith(".jar", ignoreCase = true)) {
            appRootFor(codeSource.absoluteFile.parentFile)?.let { return it }
        }

        return File(workingDirectory).absoluteFile
    }

    /**
     * The program folder that owns a jar living in [jarDirectory], or the jar's own folder when the
     * layout is unknown.
     */
    private fun appRootFor(jarDirectory: File?): File? {
        if (jarDirectory == null) return null
        // jpackage app images keep the main jar in <app>/lib/app/ (Linux),
        // <app>.app/Contents/app/ (macOS), or <app>/app/ (Windows).
        if (jarDirectory.name == "app") {
            val parent = jarDirectory.parentFile ?: return jarDirectory
            if ((parent.name == "lib" || parent.name == "Contents") && parent.parentFile != null) {
                return parent.parentFile
            }
            return parent
        }
        // Gradle's application plugin (installDist/distZip) keeps every jar in <app>/lib/.
        if (jarDirectory.name == "lib") return jarDirectory.parentFile
        return jarDirectory
    }
}
