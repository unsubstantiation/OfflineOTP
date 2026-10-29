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
import org.shadowice.flocke.andotp.Database.VaultJson
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.CryptoPrimitives
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupCodec
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupHeader
import org.shadowice.flocke.andotp.Utilities.OpenPgpPort
import org.shadowice.flocke.andotp.Utilities.QrImageDecoder
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.desktop.storage.DesktopStorage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Base64
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    fun visibilityPersistsAcrossVaultAndEncryptedBackupAndFailsClosed() {
        val (dir, controller, state) = harness()
        val editor = EntryEditor(controller, state)
        val otp = entry("Issuer", "OTP")
        val note = Entry(Entry.OTPType.NOTE, "secret", "", "Note", arrayListOf())
        val key = Entry.sshKey("public", "private", "", "Key", arrayListOf())
        for (item in listOf(otp, note, key)) assertTrue(editor.add(item))
        assertEquals(EntryEditor.TokenCopyResult.Unavailable, editor.copyToken(note))
        assertEquals(EntryEditor.TokenCopyResult.Unavailable, editor.copySshKey(key, false))
        assertTrue(editor.setDetailsVisible(note, true))
        assertTrue(editor.setDetailsVisible(key, true))
        val reopened = controller.openVault(password) as VaultController.Session.Loaded
        assertEquals(listOf(false, true, true), reopened.entries.map { it.areDetailsVisible() })
        val backup = BackupCoordinator(controller, state).encryptedBackup(password)
        val other = VaultController(DesktopStorage.open(Files.createTempDirectory("privacy-backup-").toFile()))
        val otherState = DesktopVaultState((other.createVault(password) as VaultController.Session.Loaded).key)
        assertEquals(3, BackupCoordinator(other, otherState).restoreEncrypted(password, backup))
        assertEquals(listOf(false, true, true), otherState.snapshot().map { it.areDetailsVisible() })
        assertTrue(dir.setWritable(false, false))
        try {
            assertFalse(editor.setDetailsVisible(otp, true))
            assertFalse(otp.areDetailsVisible())
            assertFalse(editor.setDetailsVisible(note, false))
            assertFalse(note.areDetailsVisible())
        } finally { assertTrue(dir.setWritable(true, false)) }
        assertTrue((controller.openVault(password) as VaultController.Session.Loaded).entries[1].areDetailsVisible())
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
    fun headerlessPre063BackupIsRejectedWithoutChangingTheVault() {
        val (dir, controller, state) = harness()
        assertTrue(EntryEditor(controller, state).add(entry("Existing", "alice")))
        val before = VaultJson.entriesToString(state.snapshot())
        val vaultFile = File(dir, Constants.FILENAME_DATABASE)
        val beforeBytes = vaultFile.readBytes()
        val document = VaultJson.entriesToString(listOf(entry("Old backup", "bob")))

        // This is a valid legacy SHA-256/AES-GCM frame, but its nonce starts with a plausible
        // current-format iteration count; rejecting it must not depend only on the header guard.
        val nonce = ByteBuffer.allocate(12).putInt(1_000).putLong(0x1020304050607080L).array()
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray(Charsets.UTF_8)), "AES")
        val headerless = nonce + CryptoPrimitives.encrypt(key, IvParameterSpec(nonce),
            document.toByteArray(Charsets.UTF_8))
        assertEquals(1_000, EncryptedBackupHeader.parse(headerless).getIterations())
        try {
            BackupCoordinator(controller, state).restoreEncrypted(password, headerless)
            throw AssertionError("headerless backup accepted")
        } catch (expected: GeneralSecurityException) {
            // Authentication as a current-format backup must fail; there is no legacy fallback.
        }

        assertEquals(before, VaultJson.entriesToString(state.snapshot()))
        assertTrue("a failed restore rewrote the vault", beforeBytes.contentEquals(vaultFile.readBytes()))
        val reopened = controller.openVault(password) as VaultController.Session.Loaded
        assertEquals(before, VaultJson.entriesToString(reopened.entries))
    }

    @Test
    fun sshKeyPairCanBeCopiedIndividuallyAndSurvivesBackup() {
        val (_, controller, state) = harness()
        val editor = EntryEditor(controller, state)
        val publicKey = "ssh-ed25519 example public\n"
        val privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "a".repeat(5000) + "\n-----END OPENSSH PRIVATE KEY-----\n"
        val entry = Entry.sshKey(publicKey, privateKey, "", "Server", arrayListOf())
        assertTrue(editor.add(entry))
        assertTrue(editor.setDetailsVisible(entry, true))
        assertEquals(EntryEditor.TokenCopyResult.Unavailable, editor.copyToken(entry))
        assertEquals(publicKey, (editor.copySshKey(entry, false) as EntryEditor.TokenCopyResult.Copied).token)
        assertEquals(privateKey, (editor.copySshKey(entry, true) as EntryEditor.TokenCopyResult.Copied).token)
        val backup = BackupCoordinator(controller, state).encryptedBackup(password)
        val otherDir = Files.createTempDirectory("ssh-backup-").toFile()
        val other = VaultController(DesktopStorage.open(otherDir))
        val otherState = DesktopVaultState((other.createVault(password) as VaultController.Session.Loaded).key)
        assertEquals(1, BackupCoordinator(other, otherState).restoreEncrypted(password, backup))
        assertEquals(publicKey, otherState.snapshot().single().getSshKeys().publicKey)
        assertEquals(privateKey, otherState.snapshot().single().getSshKeys().privateKey)
    }

    @Test
    fun sshKeyCopyRejectsMissingFieldsAndFailedPersistence() {
        val (dir, controller, state) = harness()
        val editor = EntryEditor(controller, state)
        val entry = Entry.sshKey("public", "", "", "Server", arrayListOf())
        assertTrue(editor.add(entry))
        assertTrue(editor.setDetailsVisible(entry, true))
        assertEquals(EntryEditor.TokenCopyResult.Unavailable, editor.copySshKey(entry, true))
        assertTrue(dir.setWritable(false, false))
        try {
            assertEquals(EntryEditor.TokenCopyResult.PersistenceFailed, editor.copySshKey(entry, false))
        } finally {
            assertTrue(dir.setWritable(true, false))
        }
    }

    @Test
    fun failedNoteEditsDoNotMutateTheLiveOrSavedVault() {
        val (dir, controller, state) = harness()
        val editor = EntryEditor(controller, state)
        val original = Entry(Entry.OTPType.NOTE, "original", "", "Recovery", arrayListOf())
        assertTrue(editor.add(original))
        val replacement = Entry(Entry.OTPType.NOTE, "replacement", "", "Recovery", arrayListOf())
        val added = Entry(Entry.OTPType.NOTE, "another", "", "Other", arrayListOf())
        assertTrue(dir.setWritable(false, false))
        try {
            assertFalse(editor.add(added))
            assertFalse(editor.replace(original, replacement))
            assertFalse(editor.removeAt(0))
            assertEquals(1, state.snapshot().size)
            assertEquals("original", state.snapshot().single().getNote())
        } finally {
            assertTrue(dir.setWritable(true, false))
        }
        val reopened = controller.openVault(password) as VaultController.Session.Loaded
        assertEquals("original", reopened.entries.single().getNote())
    }

    @Test
    fun notePersistsCopiesAndRestoresThroughPortableBackup() {
        val (_, controller, state) = harness()
        val editor = EntryEditor(controller, state)
        val note = Entry(Entry.OTPType.NOTE, "private key\nwith spaces 🔐", "", "Recovery", arrayListOf())
        assertTrue(editor.add(note))
        assertTrue(editor.setDetailsVisible(note, true))
        val copied = editor.copyToken(note) as EntryEditor.TokenCopyResult.Copied
        assertEquals("private key\nwith spaces 🔐", copied.token)
        val backup = BackupCoordinator(controller, state).encryptedBackup(password)
        val reopened = controller.openVault(password) as VaultController.Session.Loaded
        assertEquals(note.getNote(), reopened.entries.single().getNote())
        val otherDir = Files.createTempDirectory("note-backup-").toFile()
        val other = VaultController(DesktopStorage.open(otherDir))
        val otherState = DesktopVaultState((other.createVault(password) as VaultController.Session.Loaded).key)
        assertEquals(1, BackupCoordinator(other, otherState).restoreEncrypted(password, backup))
        assertEquals(note.getNote(), otherState.snapshot().single().getNote())
    }

    @Test
    fun encryptedBackupPreservesAllEntryTypesAndMetadata() {
        val (_, controller, state) = harness()
        val document = """[
          {"secret":"JBSWY3DPEHPK3PXP","issuer":"Équipe","label":"alice","type":"TOTP","digits":8,"algorithm":"SHA512","period":60,"last_used":1234,"used_frequency":9,"tags":["work","東京"]},
          {"secret":"JBSWY3DPEHPK3PXP","issuer":"Counter","label":"bob","type":"HOTP","digits":6,"algorithm":"SHA256","counter":42,"tags":[]},
          {"secret":"JBSWY3DPEHPK3PXP","issuer":"Steam","label":"carol","type":"STEAM","digits":5,"period":45,"tags":[]},
          {"secret":"JBSWY3DPEHPK3PXP","issuer":"Mobile","label":"dave","type":"MOTP","digits":6,"pin":"1234","tags":["private"]}
        ]"""
        val expectedMotpSecret = VaultJson.stringToEntries(document)[3].getSecret()!!
        val coordinator = BackupCoordinator(controller, state)
        assertEquals(4, coordinator.restoreEncrypted(password, EncryptedBackupCodec.seal(password, document)))
        val exported = coordinator.encryptedBackup(password)
        val imported = VaultJson.stringToEntries(EncryptedBackupCodec.open(password, exported))
        val reopened = (controller.openVault(password) as VaultController.Session.Loaded).entries
        for (entries in listOf(imported, reopened)) {
            assertEquals(4, entries.size)
            assertEquals("Équipe", entries[0].getIssuer())
            assertEquals(60, entries[0].getPeriod())
            assertEquals(TokenCalculator.HashAlgorithm.SHA512, entries[0].getAlgorithm())
            assertEquals(1234L, entries[0].getLastUsed())
            assertEquals(9L, entries[0].getUsedFrequency())
            assertEquals(listOf("work", "東京"), entries[0].getTags())
            assertEquals(Entry.OTPType.HOTP, entries[1].getType())
            assertEquals(42L, entries[1].getCounter())
            assertEquals(Entry.OTPType.STEAM, entries[2].getType())
            assertEquals(45, entries[2].getPeriod())
            assertEquals(Entry.OTPType.MOTP, entries[3].getType())
            assertEquals("1234", entries[3].getPin())
            assertTrue(entries[3].getSecret()!!.contentEquals(expectedMotpSecret))
        }
    }

    @Test
    fun failedRestorePreservesTheCurrentVaultForBothBackupTypes() {
        val (dir, controller, state) = harness()
        assertTrue(EntryEditor(controller, state).add(entry("Existing", "alice")))
        val coordinator = BackupCoordinator(controller, state, pgpPort = ReversingPgpPort())
        val document = VaultJson.entriesToString(listOf(entry("Imported", "bob")))
        val encrypted = EncryptedBackupCodec.seal(password, document)
        val pgp = document.toByteArray(Charsets.UTF_8).reversedArray()
        val before = VaultJson.entriesToString(state.snapshot())

        assertTrue(dir.setWritable(false, false))
        try {
            for (restore in listOf<() -> Unit>(
                    { coordinator.restoreEncrypted(password, encrypted) },
                    { coordinator.restoreOpenPgp(pgp, byteArrayOf(1), null) })) {
                try {
                    restore()
                    throw AssertionError("restore unexpectedly succeeded")
                } catch (expected: IllegalStateException) {
                    assertEquals(before, VaultJson.entriesToString(state.snapshot()))
                }
            }
        } finally {
            assertTrue(dir.setWritable(true, false))
        }
        val reopened = controller.openVault(password) as VaultController.Session.Loaded
        assertEquals(before, VaultJson.entriesToString(reopened.entries))
    }

    @Test
    fun emptyOrInvalidBackupDoesNotReplaceTheCurrentVault() {
        val (_, controller, state) = harness()
        assertTrue(EntryEditor(controller, state).add(entry("Existing", "alice")))
        val coordinator = BackupCoordinator(controller, state)
        val before = VaultJson.entriesToString(state.snapshot())
        for (document in listOf("[]", "not json")) {
            try {
                coordinator.restoreEncrypted(password, EncryptedBackupCodec.seal(password, document))
                throw AssertionError("invalid backup accepted")
            } catch (expected: RuntimeException) {
                assertEquals(before, VaultJson.entriesToString(state.snapshot()))
            }
        }
    }

    @Test
    fun duplicateAccountIdentitiesAreRejectedWithoutLosingEitherVault() {
        val (_, controller, state) = harness()
        assertTrue(EntryEditor(controller, state).add(entry("Existing", "alice")))
        val before = VaultJson.entriesToString(state.snapshot())
        val first = entry("Duplicate", "bob")
        val second = entry("Duplicate", "bob")
        first.setUsedFrequency(1)
        second.setUsedFrequency(2)
        val backup = EncryptedBackupCodec.seal(password, VaultJson.entriesToString(listOf(first, second)))
        try {
            BackupCoordinator(controller, state).restoreEncrypted(password, backup)
            throw AssertionError("duplicate accounts accepted")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Backup contains duplicate accounts", expected.message)
        }
        assertEquals(before, VaultJson.entriesToString(state.snapshot()))
        val reopened = controller.openVault(password) as VaultController.Session.Loaded
        assertEquals(before, VaultJson.entriesToString(reopened.entries))
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

    /** Returns fixed decoded text once per image, like scanning successive image files. */
    private class ScriptedQrDecoder(texts: List<String>) : QrImageDecoder {
        private val queue = ArrayDeque(texts)
        override fun decode(imageBytes: ByteArray): String? = queue.removeFirstOrNull()
    }

    @Test
    fun copyTokenReturnsItAndPersistsUsageMetadata() {
        val (dir, controller, state) = harness()
        val editor = EntryEditor(controller, state)
        val totp = entry("Issuer C", "carol")
        assertTrue(editor.add(totp))
        assertTrue(editor.setDetailsVisible(totp, true))
        totp.updateOTP(true)
        val token = totp.getCurrentOTP()!!

        val result = editor.copyToken(totp)
        assertTrue(result is EntryEditor.TokenCopyResult.Copied)
        assertEquals(token, (result as EntryEditor.TokenCopyResult.Copied).token)
        assertTrue(totp.getLastUsed() > 0)
        assertEquals(1L, totp.getUsedFrequency())

        val reopened = VaultController(DesktopStorage.open(dir)).openVault(password)
                as VaultController.Session.Loaded
        val stored = reopened.entries.single()
        assertEquals(1L, stored.getUsedFrequency())
        assertTrue(stored.getLastUsed() > 0)
    }

    @Test
    fun copyTokenWithoutACachedTokenIsUnavailable() {
        val (_, controller, state) = harness()
        val editor = EntryEditor(controller, state)
        val fresh = entry("Issuer D", "dave")
        assertTrue(editor.add(fresh))
        assertTrue(editor.setDetailsVisible(fresh, true))
        // Never ticked: no cached token yet, and the frequency must not be touched.
        assertEquals(null, fresh.getCurrentOTP())
        assertTrue(editor.copyToken(fresh) is EntryEditor.TokenCopyResult.Unavailable)
        assertEquals(0L, fresh.getUsedFrequency())
    }

    @Test
    fun copyTokenRejectsTheMotpNoPinPlaceholder() {
        val (_, controller, state) = harness()
        val editor = EntryEditor(controller, state)
        val motp = Entry(Entry.OTPType.MOTP, "0001020304050607", "Example", "alice", ArrayList())
        assertTrue(editor.add(motp))
        assertTrue(editor.setDetailsVisible(motp, true))
        motp.updateOTP(true)
        assertEquals("PINREQ", motp.getCurrentOTP())
        assertTrue(editor.copyToken(motp) is EntryEditor.TokenCopyResult.Unavailable)
    }

    @Test
    fun copyTokenReportsPersistenceFailureAndHandsBackNoToken() {
        val (dir, controller, state) = harness()
        val editor = EntryEditor(controller, state)
        val totp = entry("Issuer E", "erin")
        assertTrue(editor.add(totp))
        assertTrue(editor.setDetailsVisible(totp, true))
        totp.updateOTP(true)

        assertTrue(dir.setWritable(false, false))
        try {
            assertTrue(editor.copyToken(totp) is EntryEditor.TokenCopyResult.PersistenceFailed)
        } finally {
            assertTrue(dir.setWritable(true, false))
        }
    }

    @Test
    fun hotpCounterAdvanceRecomputesAndPersists() {
        val (dir, controller, state) = harness()
        val editor = EntryEditor(controller, state)
        val hotp = Entry(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", 4L, 6,
            "Issuer F", "frank", TokenCalculator.HashAlgorithm.SHA1, ArrayList())
        assertTrue(editor.add(hotp))
        assertTrue(editor.setDetailsVisible(hotp, true))
        hotp.updateOTP(false)
        val first = hotp.getCurrentOTP()
        assertEquals(4L, hotp.getCounter())

        assertTrue(editor.advanceHotp(hotp))
        assertEquals(5L, hotp.getCounter())
        assertTrue(first != hotp.getCurrentOTP())

        val reopened = VaultController(DesktopStorage.open(dir)).openVault(password)
                as VaultController.Session.Loaded
        assertEquals(5L, reopened.entries.single().getCounter())
    }

    @Test
    fun hotpAdvanceRejectsNonHotpEntriesAndTheCounterCeiling() {
        val (_, controller, state) = harness()
        val editor = EntryEditor(controller, state)
        val totp = entry("Issuer G", "gina")
        assertTrue(editor.add(totp))
        assertTrue(editor.setDetailsVisible(totp, true))
        assertFalse(editor.advanceHotp(totp))

        val maxed = Entry(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", Long.MAX_VALUE - 1, 6,
            "Issuer H", "hank", TokenCalculator.HashAlgorithm.SHA1, ArrayList())
        assertTrue(editor.add(maxed))
        assertTrue(editor.setDetailsVisible(maxed, true))
        assertFalse(editor.advanceHotp(maxed))
        assertEquals(Long.MAX_VALUE - 1, maxed.getCounter())
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
