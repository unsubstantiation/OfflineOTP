/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * End-to-end headless vectors for the desktop controller against the real java.nio stores:
 * provisioning, unlock, save, re-open through a second controller instance and backup promotion.
 */
package org.shadowice.flocke.andotp.desktop.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.desktop.storage.DesktopStorage
import java.io.File
import java.nio.file.Files
import java.util.ArrayList

class VaultControllerTest {

    private val password = "controller test password"

    private fun newController(): Pair<File, VaultController> {
        val dir = Files.createTempDirectory("offlineotp-controller-").toFile()
        return Pair(dir, VaultController(DesktopStorage.open(dir)))
    }

    private fun entry(issuer: String, label: String): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    @Test
    fun missingVaultReportsMissingAndCannotBeOpened() {
        val (dir, controller) = newController()
        assertFalse(controller.isInitialized())

        val outcome = controller.openVault(password)
        assertEquals(VaultController.Failure.MISSING,
            (outcome as VaultController.Session.Error).failure)
        assertFalse(File(dir, Constants.FILENAME_DATABASE).exists())
    }

    @Test
    fun provisioningUnlocksAnEmptyVaultAndRejectsASecondVault() {
        val (_, controller) = newController()
        val created = controller.createVault(password)
        assertTrue(created is VaultController.Session.Loaded)
        assertEquals(0, (created as VaultController.Session.Loaded).entries.size)
        assertTrue(controller.isInitialized())

        val second = controller.createVault(password)
        assertEquals(VaultController.Failure.ALREADY_EXISTS,
            (second as VaultController.Session.Error).failure)
    }

    @Test
    fun wrongPasswordIsRejectedAndTheRightOneOpensSavedEntries() {
        val (dir, controller) = newController()
        val session = controller.createVault(password) as VaultController.Session.Loaded

        val denied = controller.openVault("$password-wrong")
        assertEquals(VaultController.Failure.AUTHENTICATION_FAILED,
            (denied as VaultController.Session.Error).failure)

        val entries = arrayListOf(entry("Issuer A", "label-a"), entry("Issuer B", "label-b"))
        assertTrue(controller.save(session.key, entries))

        // A brand-new controller over the same directory proves the files are self-contained.
        val reopenedController = VaultController(DesktopStorage.open(dir))
        val reopened = reopenedController.openVault(password)
        val loaded = reopened as VaultController.Session.Loaded
        assertEquals(2, loaded.entries.size)
        assertEquals("Issuer A", loaded.entries[0].getIssuer())
        assertEquals("label-b", loaded.entries[1].getLabel())
    }

    @Test
    fun backupWritesTheSiblingBackupFile() {
        val (dir, controller) = newController()
        val session = controller.createVault(password) as VaultController.Session.Loaded
        controller.save(session.key, arrayListOf(entry("Issuer", "label")))

        assertTrue(controller.backup())
        assertTrue(File(dir, Constants.FILENAME_DATABASE_BACKUP).exists())
    }
}
