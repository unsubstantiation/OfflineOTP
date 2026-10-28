package org.shadowice.flocke.andotp.desktop.control

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.VaultJson
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupCodec
import org.shadowice.flocke.andotp.Utilities.OpenPgpBackupCodec
import org.shadowice.flocke.andotp.Utilities.OpenPgpPort
import org.shadowice.flocke.andotp.Utilities.QrImageDecoder
import org.shadowice.flocke.andotp.Utilities.QrImport
import org.shadowice.flocke.andotp.desktop.openpgp.PgpainlessOpenPgpPort
import org.shadowice.flocke.andotp.desktop.qr.ZxingQrDecoder

import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Headless backup, restore and QR-import workflows for the unlocked vault. The Swing dialogs only
 * collect passwords, key files and image files; every byte-level operation lives here and is
 * covered by headless tests. Password backups use the shared [EncryptedBackupCodec], OpenPGP the
 * [OpenPgpBackupCodec] over an injected [OpenPgpPort], and QR images a [QrImageDecoder] whose text
 * is routed by the portable [QrImport].
 */
class BackupCoordinator(
    private val controller: VaultController,
    private val state: DesktopVaultState,
    private val pgpPort: OpenPgpPort = PgpainlessOpenPgpPort(),
    private val qrDecoder: QrImageDecoder = ZxingQrDecoder()
) {

    sealed class ImportResult {
        data class Imported(val count: Int) : ImportResult()
        object NotRecognized : ImportResult()
        data class Invalid(val reason: String) : ImportResult()
    }

    fun encryptedBackup(password: String): ByteArray =
        EncryptedBackupCodec.seal(password, VaultJson.entriesToString(state.snapshot()))

    /** Restores a password backup (current or legacy framing) and persists the imported entries. */
    @Throws(GeneralSecurityException::class, IOException::class)
    fun restoreEncrypted(password: String, data: ByteArray, oldFormat: Boolean = false): Int {
        val entries = VaultJson.stringToEntries(
            EncryptedBackupCodec.open(password, data, oldFormat))
        state.load(entries)
        check(controller.save(state.key, state.snapshot())) { "Vault persistence failed" }
        return entries.size
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    fun openPgpBackup(recipientKeyBlocks: List<ByteArray>): ByteArray =
        OpenPgpBackupCodec.seal(pgpPort, VaultJson.entriesToString(state.snapshot()), recipientKeyBlocks)

    @Throws(GeneralSecurityException::class, IOException::class)
    fun restoreOpenPgp(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?): Int {
        val document = OpenPgpBackupCodec.open(pgpPort, message, secretKeyBlock, passphrase)
        val entries = VaultJson.stringToEntries(document)
        state.load(entries)
        check(controller.save(state.key, state.snapshot())) { "Vault persistence failed" }
        return entries.size
    }

    /** Decodes an image file, classifies its QR text and imports any recognized accounts. */
    fun importQrImage(imageBytes: ByteArray): ImportResult {
        val decoded = qrDecoder.decode(imageBytes) ?: return ImportResult.NotRecognized
        return when (val outcome = QrImport.classify(decoded)) {
            is QrImport.Outcome.Unrecognized -> ImportResult.NotRecognized
            is QrImport.Outcome.Invalid -> ImportResult.Invalid(outcome.reason)
            is QrImport.Outcome.Otp -> {
                val entry = Entry(outcome.result.value)
                val added = state.add(entry) && controller.save(state.key, state.snapshot())
                ImportResult.Imported(if (added) 1 else 0)
            }
            is QrImport.Outcome.Migration -> {
                val accounts = outcome.result.payload.takeAccounts()
                var count = 0
                for (account in accounts) {
                    if (state.add(Entry(account))) count++
                }
                check(controller.save(state.key, state.snapshot())) { "Vault persistence failed" }
                ImportResult.Imported(count)
            }
        }
    }
}
