/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless end-to-end vectors for entry edits, encrypted/OpenPGP backup round trips and QR
 * import, all driven through the real java.nio vault directory with in-memory PGP/QR fakes where
 * the embedded libraries would be required.
 */
package org.shadowice.flocke.andotp.desktop.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.OpenPgpPort
import org.shadowice.flocke.andotp.Utilities.QrImageDecoder
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.desktop.storage.DesktopStorage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.ArrayList
import java.util.Base64

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

    @Test
    fun exactDuplicateIsRejectedAndDuplicateIssuerHonorsThePolicy() {
        val (_, controller, state) = harness()
        val uri = "otpauth://totp/Example:carol?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        val rejecting = BackupCoordinator(controller, state,
            pgpPort = ReversingPgpPort(), qrDecoder = FixedQrDecoder(uri),
            duplicateIssuerPolicy = BackupCoordinator.DuplicateIssuerPolicy { false })

        assertTrue(rejecting.importQrImage(ByteArray(8)) is BackupCoordinator.ImportResult.Imported)
        // The issuer check runs first (Android parity): even an identical entry is an
        // unconfirmed duplicate issuer under the rejecting policy.
        assertTrue(rejecting.importQrImage(ByteArray(8))
            is BackupCoordinator.ImportResult.DuplicateIssuerRejected)

        // An allowing policy reaches the equality check, where the identical entry is a duplicate.
        val allowingSameUri = BackupCoordinator(controller, state,
            pgpPort = ReversingPgpPort(), qrDecoder = FixedQrDecoder(uri),
            duplicateIssuerPolicy = BackupCoordinator.DuplicateIssuerPolicy { true })
        assertEquals(BackupCoordinator.ImportResult.DuplicateEntry,
            allowingSameUri.importQrImage(ByteArray(8)))

        // A different account under an existing issuer is blocked by the rejecting policy.
        val other = "otpauth://totp/Example:dave?secret=JBSWY3DPK5XXE3DE&issuer=Example"
        val otherCoordinator = BackupCoordinator(controller, state,
            pgpPort = ReversingPgpPort(), qrDecoder = FixedQrDecoder(other),
            duplicateIssuerPolicy = BackupCoordinator.DuplicateIssuerPolicy { false })
        val blocked = otherCoordinator.importQrImage(ByteArray(8))
        assertTrue(blocked is BackupCoordinator.ImportResult.DuplicateIssuerRejected)
        assertEquals("Example",
            (blocked as BackupCoordinator.ImportResult.DuplicateIssuerRejected).issuer)
        assertEquals(1, state.snapshot().size)

        // The allowing policy imports the second account under the same issuer.
        val allowing = BackupCoordinator(controller, state,
            pgpPort = ReversingPgpPort(), qrDecoder = FixedQrDecoder(other),
            duplicateIssuerPolicy = BackupCoordinator.DuplicateIssuerPolicy { true })
        val result = allowing.importQrImage(ByteArray(8))
        assertEquals(1, (result as BackupCoordinator.ImportResult.Imported).count)
        assertEquals(2, state.snapshot().size)
    }

    @Test
    fun failedVaultWriteRollsTheInMemoryImportBack() {
        val (dir, controller, state) = harness()
        val uri = "otpauth://totp/Example:carol?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        val coordinator = BackupCoordinator(controller, state,
            pgpPort = ReversingPgpPort(), qrDecoder = FixedQrDecoder(uri))

        assertTrue(dir.setWritable(false, false))
        try {
            assertEquals(BackupCoordinator.ImportResult.PersistenceFailed,
                coordinator.importQrImage(ByteArray(8)))
            // The rolled-back in-memory state must not diverge from the untouched vault.
            assertEquals(0, state.snapshot().size)
        } finally {
            assertTrue(dir.setWritable(true, false))
        }
    }

    @Test
    fun multiPartMigrationIsBufferedAndOnlyCommittedWhenComplete() {
        val (_, controller, state) = harness()
        val partZero = migrationUri(0, "first")
        val partOne = migrationUri(1, "second")
        val stray = "otpauth://totp/Example:stray?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        val decoder = ScriptedQrDecoder(listOf(partZero, stray, partOne))
        val coordinator = BackupCoordinator(controller, state,
            pgpPort = ReversingPgpPort(), qrDecoder = decoder,
            duplicateIssuerPolicy = BackupCoordinator.DuplicateIssuerPolicy { true })

        val first = coordinator.importQrImage(ByteArray(8))
        assertTrue(first is BackupCoordinator.ImportResult.MigrationPart)
        assertEquals(1, (first as BackupCoordinator.ImportResult.MigrationPart).received)
        assertEquals(2, first.expected)
        assertEquals(0, state.snapshot().size) // nothing committed for a partial batch

        // A non-migration QR cannot join an open batch (Android parity).
        assertEquals(
            BackupCoordinator.ImportResult.MigrationRejection.EXPECTED_MIGRATION,
            (coordinator.importQrImage(ByteArray(8))
                as BackupCoordinator.ImportResult.MigrationRejected).why)

        val done = coordinator.importQrImage(ByteArray(8))
        assertEquals(2, (done as BackupCoordinator.ImportResult.Imported).count)
        assertEquals(listOf("first", "second"), state.snapshot().map { it.getLabel() })
    }

    @Test
    fun duplicateMigrationPartIsRejectedAndCancellationCommitsNothing() {
        val (_, controller, state) = harness()
        val decoder = ScriptedQrDecoder(listOf(migrationUri(0, "first"), migrationUri(0, "again")))
        val coordinator = BackupCoordinator(controller, state,
            pgpPort = ReversingPgpPort(), qrDecoder = decoder)

        assertTrue(coordinator.importQrImage(ByteArray(8)) is BackupCoordinator.ImportResult.MigrationPart)
        val rejected = coordinator.importQrImage(ByteArray(8))
        assertEquals(
            BackupCoordinator.ImportResult.MigrationRejection.DUPLICATE_PART,
            (rejected as BackupCoordinator.ImportResult.MigrationRejected).why)

        coordinator.cancelMigrationImport()
        assertFalse(coordinator.migrationInProgress())
        assertEquals(0, state.snapshot().size)
    }

    @Test
    fun coordinatorRestoresTheLegacyHeaderlessBackupFormat() {
        val (_, controller, state) = harness()
        val coordinator = BackupCoordinator(controller, state, pgpPort = ReversingPgpPort())
        val legacy = javaClass.getResourceAsStream("/golden/legacy-otp_accounts.json.aes")!!.readBytes()

        val count = coordinator.restoreEncrypted("golden-interop-password", legacy, oldFormat = true)
        assertEquals(2, count)
        assertEquals(2, state.snapshot().size)
    }

    /** Returns fixed decoded text once per image, like scanning successive image files. */
    private class ScriptedQrDecoder(texts: List<String>) : QrImageDecoder {
        private val queue = ArrayDeque(texts)
        override fun decode(imageBytes: ByteArray): String? = queue.removeFirstOrNull()
    }

    private fun migrationUri(partIndex: Int, label: String): String {
        val secret = byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x21, partIndex.toByte(), 1, 2, 3)
        val account = fieldBytes(1, secret) +
            fieldBytes(2, label.toByteArray()) +
            fieldBytes(3, "Example".toByteArray()) +
            // Wire enums: algorithm 0 = SHA1, digits 1 = six digits, type 2 = TOTP.
            fieldVarint(4, 0) + fieldVarint(5, 1) + fieldVarint(6, 2)
        val payload = fieldBytes(1, account) +
            fieldVarint(2, 2) + fieldVarint(3, 2) + fieldVarint(4, partIndex.toLong()) + fieldVarint(5, 77)
        return "otpauth-migration://offline?data=" + Base64.getEncoder().encodeToString(payload)
    }

    private fun fieldBytes(field: Int, bytes: ByteArray): ByteArray =
        varint((field * 8 + 2).toLong()) + varint(bytes.size.toLong()) + bytes

    private fun fieldVarint(field: Int, value: Long): ByteArray =
        varint((field * 8).toLong()) + varint(value)

    private fun varint(input: Long): ByteArray {
        var value = input
        val out = ByteArrayOutputStream()
        do {
            val next = (value and 0x7f).toInt()
            value = value ushr 7
            out.write(if (value == 0L) next else next or 0x80)
        } while (value != 0L)
        return out.toByteArray()
    }
}
