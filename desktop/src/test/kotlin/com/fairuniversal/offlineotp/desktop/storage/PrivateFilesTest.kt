/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * The desktop has no platform-provided private storage, so these modes are the only thing standing
 * between the encrypted vault and every other local account. They are asserted on POSIX hosts; a
 * non-POSIX host exercises the fallback path and skips the mode assertions rather than faking them.
 */
package com.fairuniversal.offlineotp.desktop.storage

import com.fairuniversal.offlineotp.Utilities.Constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

class PrivateFilesTest {

    private fun newDirectory(): File = Files.createTempDirectory("offlineotp-private-").toFile()

    private fun posixSupported(): Boolean =
        File(System.getProperty("java.io.tmpdir")).toPath().fileSystem
            .supportedFileAttributeViews().contains("posix")

    private fun modes(file: File): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun newDirectoryIsOwnerOnly() {
        val fresh = File(newDirectory(), "vault")
        PrivateFiles.ensureDirectory(fresh)
        assertTrue(fresh.isDirectory)
        assumeTrue(posixSupported())
        assertEquals("rwx------", modes(fresh))
    }

    @Test
    fun existingLooseDirectoryIsRepaired() {
        assumeTrue(posixSupported())
        val loose = File(newDirectory(), "old-install")
        assertTrue(loose.mkdirs())
        Files.setPosixFilePermissions(loose.toPath(),
            PosixFilePermissions.fromString("rwxr-xr-x"))
        PrivateFiles.ensureDirectory(loose)
        assertEquals("rwx------", modes(loose))
    }

    @Test
    fun createdFileIsOwnerOnlyBeforeAnyWrite() {
        val staging = File(newDirectory(), "secrets.dat.new-1")
        PrivateFiles.createPrivate(staging)
        assertTrue(staging.exists())
        assertEquals(0L, staging.length())
        assumeTrue(posixSupported())
        assertEquals("rw-------", modes(staging))
    }

    @Test
    fun restrictRepairsAWorldReadableFile() {
        assumeTrue(posixSupported())
        val loose = File(newDirectory(), Constants.FILENAME_DATABASE)
        loose.writeText("ciphertext")
        Files.setPosixFilePermissions(loose.toPath(),
            PosixFilePermissions.fromString("rw-r--r--"))
        PrivateFiles.restrict(loose)
        assertEquals("rw-------", modes(loose))
        assertEquals("ciphertext", loose.readText())
    }

    @Test
    fun restrictIgnoresAMissingFile() {
        val directory = newDirectory()
        PrivateFiles.restrict(File(directory, "absent.dat"))
        assertTrue(!File(directory, "absent.dat").exists())
    }

    @Test
    fun vaultWritesLeaveNoReadableFileBehind() {
        assumeTrue(posixSupported())
        val directory = newDirectory()
        val vault = File(directory, Constants.FILENAME_DATABASE)
        val store = NioAtomicFileStore(vault)
        store.writeAtomic("first".toByteArray())
        store.writeAtomic("second".toByteArray())
        assertEquals("second", String(store.read()))
        assertEquals("rw-------", modes(vault))
        // The staging file must not survive promotion, loosely permissioned or otherwise.
        assertEquals(emptyList<String>(),
            directory.listFiles()!!.map { it.name }.filter { it.contains(".new-") })
    }

    @Test
    fun settingsWritesAreOwnerOnly() {
        assumeTrue(posixSupported())
        val file = File(newDirectory(), DesktopStorage.SETTINGS_FILE)
        DesktopSettings(file).putString("ui_sort_mode", "ISSUER")
        assertEquals("ISSUER", DesktopSettings(file).getString("ui_sort_mode", ""))
        assertEquals("rw-------", modes(file))
    }

    @Test
    fun openingTheVaultTightensFilesAnOlderBuildLeftLoose() {
        assumeTrue(posixSupported())
        val install = File(newDirectory(), "portable")
        assertTrue(install.mkdirs())
        Files.setPosixFilePermissions(install.toPath(),
            PosixFilePermissions.fromString("rwxr-xr-x"))
        val names = arrayOf(Constants.FILENAME_DATABASE, Constants.FILENAME_DATABASE_BACKUP,
            DesktopStorage.SETTINGS_FILE, DesktopStorage.HARDWARE_UNLOCK_FILE)
        for (name in names) {
            val file = File(install, name)
            file.writeText("legacy")
            Files.setPosixFilePermissions(file.toPath(),
                PosixFilePermissions.fromString("rw-r--r--"))
        }
        DesktopStorage.open(install)
        assertEquals("rwx------", modes(install))
        for (name in names) {
            assertEquals(name, "rw-------", modes(File(install, name)))
        }
        // No group/other bit survives anywhere in the repaired directory.
        val loose = Files.getPosixFilePermissions(install.toPath()).filter {
            it in setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
        }
        assertEquals(emptyList<PosixFilePermission>(), loose)
    }
}
