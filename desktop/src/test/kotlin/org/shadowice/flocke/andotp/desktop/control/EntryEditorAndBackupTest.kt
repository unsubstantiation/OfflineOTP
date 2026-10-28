/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless end-to-end vectors for entry edits, encrypted/OpenPGP backup round trips and QR
 * import, all driven through the real java.nio vault directory with in-memory PGP/QR fakes where
 * the embedded libraries would be required.
 */
package org.shadowice.flocke.andotp.desktop.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.OpenPgpPort
import org.shadowice.flocke.andotp.Utilities.QrImageDecoder
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.desktop.storage.DesktopStorage
import java.io.File
import java.nio.file.Files
import java.util.ArrayList

class EntryEditorAndBackupTest {

    private val password = "editor-backup-test-password"

    private fun harness(): Triple<File, VaultController, DesktopVaultState> {
        val dir = Files.createTempDirectory("offlineotp-editor-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault(password) as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        return Triple(dir, controller, state)
    }

    private fun entry(issuer: String, label: String): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    private class ReversingPgpPort : OpenPgpPort {
        override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>) =
            plaintext.reversedArray()
        override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?) =
            message.reversedArray()
    }

    private class FixedQrDecoder(private val text: String?) : QrImageDecoder {
        override fun decode(imageBytes: ByteArray): String? = text
    }

    @Test
    fun editsPersistAndAreVisibleToANewController() {
        val (dir, controller, state) = harness()
        val editor = EntryEditor(controller, state)

        assertTrue(editor.add(entry("Issuer A", "alice")))
        assertTrue(editor.add(entry("Issuer B", "bob")))
        assertTrue(editor.removeAt(0))
        assertEquals(1, state.snapshot().size)

        val reopened = VaultController(DesktopStorage.open(dir)).openVault(password)
                as VaultController.Session.Loaded
        assertEquals(1, reopened.entries.size)
        assertEquals("Issuer B", reopened.entries[0].getIssuer())
    }

    @Test
    fun encryptedBackupRoundTripsThroughTheSharedCodec() {
        val (_, controller, state) = harness()
        EntryEditor(controller, state).add(entry("Issuer", "label"))
        val coordinator = BackupCoordinator(controller, state)

        val blob = coordinator.encryptedBackup(password)
        val imported = coordinator.restoreEncrypted(password, blob)
        assertEquals(1, imported)
        assertEquals("Issuer", state.snapshot()[0].getIssuer())
    }

    @Test
    fun openPgpBackupRoundTripsThroughTheInjectedPort() {
        val (_, controller, state) = harness()
        EntryEditor(controller, state).add(entry("PGP Issuer", "pgp"))
        val coordinator = BackupCoordinator(controller, state, pgpPort = ReversingPgpPort())

        val message = coordinator.openPgpBackup(listOf("cert".toByteArray()))
        val imported = coordinator.restoreOpenPgp(
            message, "secret-key".toByteArray(), null)
        assertEquals(1, imported)
        assertEquals("PGP Issuer", state.snapshot()[0].getIssuer())
    }

    @Test
    fun qrImportAddsAnOtpauthEntryAndPersistsIt() {
        val (dir, controller, state) = harness()
        val uri = "otpauth://totp/Example:carol?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        val coordinator = BackupCoordinator(
            controller, state,
            pgpPort = ReversingPgpPort(),
            qrDecoder = FixedQrDecoder(uri))

        val result = coordinator.importQrImage(ByteArray(8))
        assertTrue(result is BackupCoordinator.ImportResult.Imported)
        assertEquals(1, (result as BackupCoordinator.ImportResult.Imported).count)

        val reopened = VaultController(DesktopStorage.open(dir)).openVault(password)
                as VaultController.Session.Loaded
        assertEquals(1, reopened.entries.size)
        assertEquals("Example", reopened.entries[0].getIssuer())
    }

    @Test
    fun unreadableQrContentIsNotAnImport() {
        val (_, controller, state) = harness()
        val coordinator = BackupCoordinator(
            controller, state,
            pgpPort = ReversingPgpPort(),
            qrDecoder = FixedQrDecoder(null))

        assertEquals(BackupCoordinator.ImportResult.NotRecognized,
            coordinator.importQrImage(ByteArray(8)))
        assertEquals(0, state.snapshot().size)
    }
}
