/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Vectors for the portable program-folder rule: a Debian or Windows build keeps its vault beside
 * itself, never in the user's profile, and an explicit override always wins.
 */
package org.shadowice.flocke.andotp.desktop.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopPortableLocationsTest {

    private val workingDirectory = File(System.getProperty("java.io.tmpdir"), "offlineotp-cwd").path

    @Test
    fun anExplicitFolderAlwaysWins() {
        val configured = File(System.getProperty("java.io.tmpdir"), "portable-home").path
        val resolved = PortableLocations.resolve(
            configured, "/opt/OfflineOTP/bin/OfflineOTP",
            File("/somewhere/else/app.jar"), workingDirectory)
        assertEquals(File(configured).absoluteFile, resolved)
        // A blank or whitespace-only value is ignored, not treated as "the current folder".
        val blank = PortableLocations.resolve("   ", "/opt/OfflineOTP/bin/OfflineOTP", null, workingDirectory)
        assertEquals(File("/opt/OfflineOTP"), blank)
    }

    @Test
    fun theJpackageLauncherSelectsTheFolderAboveBin() {
        val launcher = File("/opt/OfflineOTP/bin/OfflineOTP")
        assertEquals(File("/opt/OfflineOTP"),
            PortableLocations.resolve(null, launcher.path, File("/opt/OfflineOTP/lib/app/desktop.jar"),
                workingDirectory))
        // Windows uses the same layout with a .exe launcher.
        assertEquals(File("/opt/OfflineOTP"),
            PortableLocations.resolve(null, File(File("/opt/OfflineOTP/bin"), "OfflineOTP.exe").path, null,
                workingDirectory))
        // A launcher anywhere else (a plain copy) keeps its own folder.
        assertEquals(File("/home/user/Downloads"),
            PortableLocations.resolve(null, "/home/user/Downloads/OfflineOTP", null, workingDirectory))
    }

    @Test
    fun aJarLaunchFindsTheProgramFolderAroundTheJar() {
        // A plain `java -jar` next to the app: the jar's own folder.
        assertEquals(File("/home/user/apps/OfflineOTP"), PortableLocations.resolve(null, null,
            File("/home/user/apps/OfflineOTP/desktop.jar"), workingDirectory))
        // Gradle's application plugin distribution: <app>/lib/desktop.jar.
        assertEquals(File("/opt/OfflineOTP"), PortableLocations.resolve(null, null,
            File("/opt/OfflineOTP/lib/desktop.jar"), workingDirectory))
        // jpackage app image: <app>/lib/app/desktop.jar.
        assertEquals(File("/opt/OfflineOTP"), PortableLocations.resolve(null, null,
            File("/opt/OfflineOTP/lib/app/desktop.jar"), workingDirectory))
    }

    @Test
    fun explodedClassesFallBackToTheWorkingDirectory() {
        val classes = File("/home/user/OfflineOTP/desktop/build/classes/kotlin/main")
        val resolved = PortableLocations.resolve(null, null, classes, workingDirectory)
        assertEquals(File(workingDirectory).absoluteFile, resolved)
        // A relative working directory is still made absolute, so the vault path is unambiguous.
        assertTrue(PortableLocations.resolve(null, null, null, "build/run").isAbsolute)
    }

    @Test
    fun theVaultLivesInADataSubfolderOfTheProgramFolder() {
        assertEquals("data", DesktopStorage.DATA_DIRECTORY)
        assertEquals("offlineotp.home", DesktopStorage.HOME_PROPERTY)
        assertEquals("OFFLINEOTP_HOME", DesktopStorage.HOME_ENVIRONMENT)
        assertEquals("jpackage.app-path", DesktopStorage.JPACKAGE_LAUNCHER_PROPERTY)
        // The default directory is the program folder's data subfolder, whatever the program folder
        // resolved to; it is never a fixed dot-folder inside the user's profile.
        val default = DesktopStorage.defaultDirectory()
        assertEquals(DesktopStorage.DATA_DIRECTORY, default.name)
        assertEquals(DesktopStorage.programDirectory().absoluteFile, default.parentFile)
        assertFalse(default.path.contains(".offlineotp"))
    }

    @Test
    fun anUnusableDirectoryIsReportedWithTheOverrideHint() {
        val blocked = Files.createTempFile("offlineotp-blocked-", ".dat").toFile()
        try {
            // A file where the vault directory should be: mkdirs fails, so opening must throw
            // instead of writing the vault next to the user's files.
            val failure = runCatching { DesktopStorage.open(blocked) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertTrue(failure!!.message!!.contains(blocked.path))
        } finally {
            blocked.delete()
        }

        val readOnly = Files.createTempDirectory("offlineotp-readonly-").toFile()
        try {
            if (readOnly.setWritable(false, false) && !readOnly.canWrite()) {
                val failure = runCatching { DesktopStorage.open(readOnly) }.exceptionOrNull()
                assertTrue(failure is java.io.IOException)
                assertTrue(failure!!.message!!.contains(DesktopStorage.HOME_PROPERTY))
            }
        } finally {
            readOnly.setWritable(true, false)
            readOnly.delete()
        }
    }
}
