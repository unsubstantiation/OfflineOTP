package org.shadowice.flocke.andotp.desktop.control

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.VaultJson
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupCodec
import org.shadowice.flocke.andotp.Utilities.MigrationBatchCollector
import org.shadowice.flocke.andotp.Utilities.MigrationBatchStatus
import org.shadowice.flocke.andotp.Utilities.MigrationParseResult
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
 *
 * QR import mirrors the Android flow: a duplicate issuer is only imported when
 * [duplicateIssuerPolicy] confirms it, and a multi-QR Google Authenticator export is accumulated in
 * a [MigrationBatchCollector] until the whole batch is present — a partial batch is never committed.
 */
class BackupCoordinator(
    private val controller: VaultController,
    private val state: DesktopVaultState,
    private val pgpPort: OpenPgpPort = PgpainlessOpenPgpPort(),
    private val qrDecoder: QrImageDecoder = ZxingQrDecoder(),
    private val duplicateIssuerPolicy: DuplicateIssuerPolicy = DuplicateIssuerPolicy { false }
) {

    /** UI seam for the Android "add anyway?" duplicate-issuer confirmation. */
    fun interface DuplicateIssuerPolicy {
        fun allowDuplicateIssuer(issuer: String): Boolean
    }

    sealed class ImportResult {
        data class Imported(val count: Int) : ImportResult()
        object NotRecognized : ImportResult()
        data class Invalid(val reason: String) : ImportResult()
        /** An entry equal to one already in the vault. */
        object DuplicateEntry : ImportResult()
        /** The issuer already existed and the policy did not authorize another account. */
        data class DuplicateIssuerRejected(val issuer: String) : ImportResult()
        /** The in-memory import worked but the vault write failed; the memory change was rolled back. */
        object PersistenceFailed : ImportResult()
        /** One more QR of a multi-part export was accepted; [received] of [expected] parts are buffered. */
        data class MigrationPart(val received: Int, val expected: Int) : ImportResult()
        enum class MigrationRejection { DUPLICATE_PART, DIFFERENT_EXPORT, TOO_MANY, STALE, EXPECTED_MIGRATION }
        data class MigrationRejected(val why: MigrationRejection) : ImportResult()
    }

    /**
     * The desktop session has no Android-style generation counter; the collector is rebuilt per
     * unlocked session (the coordinator itself is) and always runs in the ready state.
     */
    private val sessionGeneration = 0L
    private var migration: MigrationBatchCollector? = null

    fun encryptedBackup(password: String): ByteArray =
        EncryptedBackupCodec.seal(password, VaultJson.entriesToString(state.snapshot()))

    /** Restores a password backup and persists the imported entries. */
    @Throws(GeneralSecurityException::class, IOException::class)
    fun restoreEncrypted(password: String, data: ByteArray): Int {
        return restoreDocument(EncryptedBackupCodec.open(password, data))
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    fun openPgpBackup(recipientKeyBlocks: List<ByteArray>): ByteArray =
        OpenPgpBackupCodec.seal(pgpPort, VaultJson.entriesToString(state.snapshot()), recipientKeyBlocks)

    @Throws(GeneralSecurityException::class, IOException::class)
    fun restoreOpenPgp(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?): Int {
        return restoreDocument(OpenPgpBackupCodec.open(pgpPort, message, secretKeyBlock, passphrase))
    }

    /** Save before replacing the live state; a failed restore must not discard the current entries. */
    private fun restoreDocument(document: String): Int {
        val entries = VaultJson.stringToEntries(document)
        var installed = false
        try {
            // Android also rejects an empty import rather than replacing a vault with it.
            require(entries.isNotEmpty()) { "Backup contains no entries" }
            // EntryList treats equal account identities as one entry. Reject ambiguous backups
            // rather than report success and silently drop their distinct metadata on the next save.
            require(entries.toHashSet().size == entries.size) { "Backup contains duplicate accounts" }
            check(controller.save(state.key, entries)) { "Vault persistence failed" }
            state.load(entries)
            installed = true
            return entries.size
        } finally {
            if (!installed) entries.forEach { it.destroy() }
        }
    }

    /** True while a multi-part Google export is waiting for more QR images. */
    fun migrationInProgress(): Boolean = migration != null

    /** Abandons a multi-part import and wipes every buffered seed. */
    fun cancelMigrationImport() {
        migration?.close()
        migration = null
    }

    /** Decodes an image file, classifies its QR text and imports any recognized accounts. */
    fun importQrImage(imageBytes: ByteArray): ImportResult =
        importQrImage(imageBytes, duplicateIssuerPolicy)

    /** Same as [importQrImage], but with a call-specific duplicate-issuer policy (the Swing
     *  confirmation dialog uses this; headless tests inject a fixed policy). */
    fun importQrImage(imageBytes: ByteArray, policy: DuplicateIssuerPolicy): ImportResult {
        val decoded = qrDecoder.decode(imageBytes) ?: return ImportResult.NotRecognized
        val outcome = QrImport.classify(decoded)
        // While a multi-part export is open, only another migration QR may join it (Android parity).
        if (migration != null && outcome !is QrImport.Outcome.Migration) {
            if (outcome is QrImport.Outcome.Otp) outcome.result.value.close()
            return ImportResult.MigrationRejected(ImportResult.MigrationRejection.EXPECTED_MIGRATION)
        }
        return when (outcome) {
            is QrImport.Outcome.Unrecognized -> ImportResult.NotRecognized
            is QrImport.Outcome.Invalid -> ImportResult.Invalid(outcome.reason)
            is QrImport.Outcome.Otp -> {
                // The parser result owns a seed; the Entry copies it, so close it in every branch.
                try {
                    importSingle(Entry(outcome.result.value), policy)
                } finally {
                    outcome.result.value.close()
                }
            }
            is QrImport.Outcome.Migration -> handleMigration(outcome.result, policy)
        }
    }

    private fun importSingle(entry: Entry, policy: DuplicateIssuerPolicy): ImportResult {
        val issuer = entry.getIssuer().trim()
        if (issuer.isNotEmpty() && state.entryList.hasIssuer(issuer) &&
            !policy.allowDuplicateIssuer(issuer)) {
            entry.destroy()
            return ImportResult.DuplicateIssuerRejected(issuer)
        }
        val added = state.add(entry)
        if (!added) {
            entry.destroy()
            return ImportResult.DuplicateEntry
        }
        if (!controller.save(state.key, state.snapshot())) {
            rollbackTrailing(1)
            return ImportResult.PersistenceFailed
        }
        return ImportResult.Imported(1)
    }

    private fun handleMigration(parsed: MigrationParseResult.Success,
                                policy: DuplicateIssuerPolicy): ImportResult {
        // While collecting, a non-migration QR can never join the batch (Android parity).
        val collector = migration ?: MigrationBatchCollector(sessionGeneration).also { migration = it }
        // collector.add() consumes the payload (including its seed) on every branch.
        return when (val status = collector.add(parsed.payload, sessionGeneration, true)) {
            MigrationBatchStatus.ADDED ->
                ImportResult.MigrationPart(collector.received, collector.expected)
            MigrationBatchStatus.COMPLETE -> completeMigration(collector, policy)
            MigrationBatchStatus.DUPLICATE ->
                ImportResult.MigrationRejected(ImportResult.MigrationRejection.DUPLICATE_PART)
            MigrationBatchStatus.DIFFERENT_EXPORT ->
                ImportResult.MigrationRejected(ImportResult.MigrationRejection.DIFFERENT_EXPORT)
            MigrationBatchStatus.TOO_MANY -> {
                migration = null
                ImportResult.MigrationRejected(ImportResult.MigrationRejection.TOO_MANY)
            }
            MigrationBatchStatus.STALE -> {
                migration = null
                ImportResult.MigrationRejected(ImportResult.MigrationRejection.STALE)
            }
        }
    }

    private fun completeMigration(collector: MigrationBatchCollector,
                                  policy: DuplicateIssuerPolicy): ImportResult {
        migration = null
        val accounts = collector.takeAccounts(sessionGeneration, true)
            ?: return ImportResult.MigrationRejected(ImportResult.MigrationRejection.STALE)
        val entries = ArrayList<Entry>(accounts.size)
        try {
            for (account in accounts) {
                try {
                    entries.add(Entry(account))
                } catch (_: RuntimeException) {
                    // Skip an account the Entry model rejects; its validated seed is closed below.
                }
            }
        } finally {
            accounts.forEach { runCatching { it.close() } }
        }
        return commitBatch(entries, policy)
    }

    /**
     * Adds the batch in one transaction-like step: duplicate issuers go through the same policy,
     * exact duplicates are skipped, and a failed vault write rolls the appended entries back.
     */
    private fun commitBatch(entries: List<Entry>, policy: DuplicateIssuerPolicy): ImportResult {
        var appended = 0
        for (entry in entries) {
            val issuer = entry.getIssuer().trim()
            if (issuer.isNotEmpty() && state.entryList.hasIssuer(issuer) &&
                !policy.allowDuplicateIssuer(issuer)) {
                entry.destroy()
                continue
            }
            if (state.add(entry)) appended++ else entry.destroy()
        }
        if (appended == 0) return ImportResult.Imported(0)
        if (!controller.save(state.key, state.snapshot())) {
            rollbackTrailing(appended)
            return ImportResult.PersistenceFailed
        }
        return ImportResult.Imported(appended)
    }

    /** Removes the [count] entries most recently appended, restoring the pre-import in-memory list. */
    private fun rollbackTrailing(count: Int) {
        repeat(count) {
            val size = state.snapshot().size
            if (size > 0) state.removeAt(size - 1)
        }
    }
}
