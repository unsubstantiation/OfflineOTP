/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Cross-platform interop vectors. The checked-in golden blobs were produced from the shared core
 * codecs with fixed salts/nonces/iterations (see golden/README.md); the SHA-256 pins make any
 * silent format change fail loudly. The same bytes must open through the desktop storage stack,
 * the encrypted-backup codec and its legacy SHA-256 path.
 */
package org.shadowice.flocke.andotp.desktop.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.VaultJson
import org.shadowice.flocke.andotp.desktop.control.BackupCoordinator
import org.shadowice.flocke.andotp.desktop.control.DesktopVaultState
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupCodec
import org.shadowice.flocke.andotp.desktop.control.VaultController
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest

class GoldenInteropTest {

    private val password = "golden-interop-password"

    private val expectedHashes = mapOf(
        "secrets.dat" to "f2d570403c9644864a316bff851bf578029fb1b4b834b5f6ef5eba7820fba556",
        "vault.properties" to "14f37ba98dfae0f07b8cbc317ec3d4ab4b667e5f7f43462aabc054fbd943e0ac",
        "otp_accounts.json.aes" to "a2451ff086486ff3cb0e82ae9d19d55c10de6943924ffab13143273a432d760f",
        "legacy-otp_accounts.json.aes" to "eb4e9eb3cf0f14d77896d47883a6c6498db0534e2fe8727fb77454b8de42374f",
        "desktop-export.json.aes" to "44ed6234519c772056ad314fb454415cb216b03431ccea25b34a88ff2751cbc9",
        "desktop-note-export.json.aes" to "8f0491db28888e5a5f729be396db6b6e16336f24a42ba05185fe44c3d27e25cc",
        "desktop-privacy-export.json.aes" to "62505c92f5ebfff957488bdf6cb5bbbc7f1054ca90ba5229c7cd74ad95971c2b",
        "desktop-ssh-export.json.aes" to "d1255eb6552e004a4b1e0f7fa28eb38ea2e8f1450ad57614b48dd7317bdc97ee",
        "expected-vault.json" to "33cb8250edad6d9a14443b47432647c479cd693300df88143b323b210b541db3"
    )

    @Test
    fun goldenBlobsAreByteStable() {
        for ((name, expectedHash) in expectedHashes) {
            assertEquals("golden blob changed: $name", expectedHash, sha256(resource(name)))
        }
    }

    @Test
    fun desktopPrivacyExportRestoresMixedVisibilityChoices() {
        val json = EncryptedBackupCodec.open(password, resource("desktop-privacy-export.json.aes"), false)
        val entries = VaultJson.stringToEntries(json)
        assertEquals(listOf(true, false, false, true), entries.map { it.areDetailsVisible() })
        assertEquals(listOf(Entry.OTPType.TOTP, Entry.OTPType.HOTP, Entry.OTPType.NOTE, Entry.OTPType.SSH_KEY),
            entries.map { it.getType() })
    }

    @Test
    fun goldenVaultOpensThroughTheDesktopStorageStack() {
        val dir = Files.createTempDirectory("offlineotp-golden-").toFile()
        File(dir, Constants.FILENAME_DATABASE).writeBytes(resource("secrets.dat"))
        File(dir, DesktopStorage.SETTINGS_FILE).writeBytes(resource("vault.properties"))

        val controller = VaultController(DesktopStorage.open(dir))
        val session = controller.openVault(password)
        val loaded = session as VaultController.Session.Loaded
        assertEquals(2, loaded.entries.size)

        val first = loaded.entries[0]
        assertEquals(Entry.OTPType.TOTP, first.getType())
        assertEquals("Golden Issuer", first.getIssuer())
        assertEquals("alice@example.com", first.getLabel())
        assertEquals(6, first.getDigits())
        assertEquals(30, first.getPeriod())

        val second = loaded.entries[1]
        assertEquals(Entry.OTPType.HOTP, second.getType())
        assertEquals("Counter Service", second.getIssuer())
        assertEquals("bob@example.com", second.getLabel())
        assertEquals(8, second.getDigits())
        assertEquals(9L, second.getCounter())

        // The vault file keeps the documented nonce-prefixed GCM framing.
        val blob = resource("secrets.dat")
        for (index in 0 until Constants.ENCRYPTION_IV_LENGTH) {
            assertEquals((0x10 + index).toByte(), blob[index])
        }

        val denied = controller.openVault("$password-wrong")
        assertEquals(VaultController.Failure.AUTHENTICATION_FAILED,
            (denied as VaultController.Session.Error).failure)
    }

    @Test
    fun goldenBackupBlobsOpenInCurrentAndLegacyFormats() {
        val expected = String(resource("expected-vault.json"), Charsets.UTF_8)

        val current = resource("otp_accounts.json.aes")
        // Framing: 4-byte big-endian iteration count, then the 12-byte salt.
        assertEquals(Constants.PBKDF2_MIN_BACKUP_ITERATIONS, ByteBuffer.wrap(current).getInt(0))
        assertEquals(expected, EncryptedBackupCodec.open(password, current, false))

        val legacy = resource("legacy-otp_accounts.json.aes")
        // Legacy files carry no iteration/salt header: the fixed nonce starts at byte zero.
        for (index in 0 until Constants.ENCRYPTION_IV_LENGTH) {
            assertEquals((0x40 + index).toByte(), legacy[index])
        }
        assertEquals(expected, EncryptedBackupCodec.open(password, legacy, true))

        try {
            EncryptedBackupCodec.open("$password-wrong", current, false)
            throw AssertionError("wrong backup password accepted")
        } catch (expectedError: java.security.GeneralSecurityException) {
            // GCM tag verification must fail.
        }
        assertTrue(expected.contains("Golden Issuer"))
    }

    @Test
    fun desktopRestoresSharedCurrentAndLegacyBackupsAndExportsTheSameDocument() {
        val expected = String(resource("expected-vault.json"), Charsets.UTF_8)
        for ((name, legacy) in listOf("otp_accounts.json.aes" to false,
                "desktop-export.json.aes" to false, "legacy-otp_accounts.json.aes" to true)) {
            val dir = Files.createTempDirectory("offlineotp-backup-interop-").toFile()
            val controller = VaultController(DesktopStorage.open(dir))
            val loaded = controller.createVault(password) as VaultController.Session.Loaded
            val state = DesktopVaultState(loaded.key)
            val coordinator = BackupCoordinator(controller, state)
            assertEquals(2, coordinator.restoreEncrypted(password, resource(name), legacy))
            assertEquals(expected, VaultJson.entriesToString(state.snapshot()))
            val reopened = controller.openVault(password) as VaultController.Session.Loaded
            assertEquals(expected, VaultJson.entriesToString(reopened.entries))
            // Android's backup task reads the same codec and VaultJson document.
            val desktopBackup = coordinator.encryptedBackup(password)
            assertEquals(expected, EncryptedBackupCodec.open(password, desktopBackup, false))
        }
    }

    @Test
    fun desktopSshExportPreservesBothKeyFieldsAndOtpAccounts() {
        val document = EncryptedBackupCodec.open(password, resource("desktop-ssh-export.json.aes"), false)
        val entries = VaultJson.stringToEntries(document)
        assertEquals(3, entries.size)
        assertEquals(Entry.OTPType.SSH_KEY, entries[2].getType())
        assertEquals("ssh-ed25519 example test@example\n", entries[2].getSshKeys().publicKey)
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\n" + "X".repeat(5000) +
            "\n-----END OPENSSH PRIVATE KEY-----\n", entries[2].getSshKeys().privateKey)
    }

    @Test
    fun desktopNoteExportPreservesNotesAndOtpAccounts() {
        val document = EncryptedBackupCodec.open(password, resource("desktop-note-export.json.aes"), false)
        val entries = VaultJson.stringToEntries(document)
        assertEquals(3, entries.size)
        assertEquals(Entry.OTPType.TOTP, entries[0].getType())
        assertEquals(Entry.OTPType.HOTP, entries[1].getType())
        assertEquals(Entry.OTPType.NOTE, entries[2].getType())
        assertEquals("recovery key\nspaces 🔐", entries[2].getNote())
        assertEquals(listOf("private"), entries[2].getTags())
    }

    private fun resource(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/golden/$name")
            ?: throw IllegalStateException("missing golden resource: $name")
        return stream.use { it.readBytes() }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format("%02x", it) }
}
