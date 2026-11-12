package com.fairuniversal.offlineotp.desktop.storage

import com.fairuniversal.offlineotp.Utilities.Constants
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DesktopStorageLocationTest {
    @Test fun capturesTheActualDirectoryWithoutCreatingOrOpeningAnything() {
        val root = Files.createTempDirectory("storage-location-").toFile()
        try {
            val directory = File(root, "unused/../private/data")
            val location = DesktopStorageLocation(directory)
            assertEquals(directory.absolutePath, location.dataDirectory)
            assertFalse(directory.exists())
            assertEquals(Constants.FILENAME_DATABASE, location.vaultFilename)
            assertEquals(Constants.FILENAME_DATABASE_BACKUP, location.backupFilename)
            assertEquals(DesktopStorage.SETTINGS_FILE, location.settingsFilename)
            assertEquals(DesktopStorage.HARDWARE_UNLOCK_FILE, location.hardwareFilename)
            assertEquals("DesktopStorageLocation(redacted)", location.toString())
        } finally { root.deleteRecursively() }
    }
    @Test fun doesNotRecomputeFromGlobalOverridesOrInterpretUntrustedPathText() {
        val previous = System.getProperty(DesktopStorage.HOME_PROPERTY)
        try {
            val actual = File("<html>private & data").absoluteFile
            val location = DesktopStorageLocation(actual)
            System.setProperty(DesktopStorage.HOME_PROPERTY, "some-other-folder")
            assertEquals(actual.absolutePath, location.dataDirectory)
            assertFalse(location.toString().contains("private"))
        } finally {
            if (previous == null) System.clearProperty(DesktopStorage.HOME_PROPERTY)
            else System.setProperty(DesktopStorage.HOME_PROPERTY, previous)
        }
    }
}
