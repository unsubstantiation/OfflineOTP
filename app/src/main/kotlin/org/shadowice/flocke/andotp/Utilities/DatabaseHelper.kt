/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.preference.PreferenceManager

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.VaultJson

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.BadPaddingException
import javax.crypto.SecretKey

/** Authenticated, bounded vault file I/O and atomic settings-journal recovery. */
object DatabaseHelper {
    /** The lock every vault file transaction is serialized on; `Settings`/`KeyStoreHelper` share it. */
    @JvmField
    val DatabaseFileLock: Any = VaultSession.MutationLock

    const val TRANSACTION_ID = "vault_transaction_id"
    const val PENDING_FILE = "secrets.pending"

    // A failed preference commit leaves process memory ambiguous. Require restart.
    private var transactionPoisoned = false

    // DatabaseFileLock is reentrant. Settings construction (or a preference listener) inside a
    // metadata callback must not mistake the live journal for an abandoned startup transaction.
    // Accessed only under DatabaseFileLock; other threads already wait for the transaction.
    private var metadataCommitInProgress = false

    enum class Status { SUCCESS, MISSING, MISSING_KEY, AUTHENTICATION_FAILED, CORRUPT, IO_FAILURE }

    /** One-use wrapper around [VaultReadResult]; entry ownership transfers on [requireEntries]. */
    class LoadResult private constructor(private val result: VaultReadResult) : AutoCloseable {
        @JvmField
        val status: Status = statusOf((result as? VaultReadResult.Unavailable)?.error)

        fun isSuccess(): Boolean = result is VaultReadResult.Loaded

        fun requireEntries(): ArrayList<Entry> {
            if (!isSuccess()) throw VaultException.create(status)
            return (result as VaultReadResult.Loaded).takeEntries()
        }

        override fun close() {
            (result as? VaultReadResult.Loaded)?.close()
        }

        companion object {
            @JvmSynthetic
            internal fun create(result: VaultReadResult): LoadResult = LoadResult(result)
        }
    }

    /** Thrown when a caller accesses entries from an unavailable vault. */
    class VaultException private constructor(@JvmField val status: Status) :
        IllegalStateException("Vault unavailable: $status") {
        companion object {
            @JvmSynthetic
            internal fun create(status: Status): VaultException = VaultException(status)
        }
    }

    /** Maps [VaultReadError] to [Status]; `RECOVERY_REQUIRED` maps to `IO_FAILURE`. */
    private fun statusOf(error: VaultReadError?): Status = when (error) {
        null -> Status.SUCCESS
        VaultReadError.MISSING -> Status.MISSING
        VaultReadError.MISSING_KEY -> Status.MISSING_KEY
        VaultReadError.AUTHENTICATION_FAILED -> Status.AUTHENTICATION_FAILED
        VaultReadError.CORRUPT -> Status.CORRUPT
        VaultReadError.IO_FAILURE -> Status.IO_FAILURE
        VaultReadError.RECOVERY_REQUIRED -> Status.IO_FAILURE
    }

    private fun file(context: Context, name: String): File = File(context.getFilesDir(), name)

    private fun unavailable(error: VaultReadError): VaultReadResult = VaultReadResult.Unavailable(error)

    private fun rejected(error: VaultWriteError): VaultWriteResult = VaultWriteResult.Rejected(error)

    private fun recoveryRequired(state: VaultCommitState): VaultWriteResult {
        VaultSession.fail(SessionFailure.RECOVERY_REQUIRED)
        return VaultWriteResult.RecoveryRequired(state)
    }

    private fun readFailure(result: VaultReadResult.Unavailable): VaultWriteResult {
        if (result.error == VaultReadError.RECOVERY_REQUIRED) return recoveryRequired(VaultCommitState.UNKNOWN)
        return rejected(VaultWriteError.valueOf(result.error.name))
    }

    /** Used before key selection at startup. Journal contains ciphertext and a UUID, never raw keys. */
    @JvmStatic
    fun recoverPendingDatabase(context: Context): Boolean {
        synchronized(DatabaseFileLock) {
            return recoverPendingDatabaseLocked(context, null)
        }
    }

    /** Caller holds DatabaseFileLock. A live commit must promote its own journal, not just find no work. */
    private fun recoverPendingDatabaseLocked(context: Context, expectedId: String?): Boolean {
        if (transactionPoisoned) return false
        if (metadataCommitInProgress) return false
        val pending = file(context, PENDING_FILE)
        if (!FileHelper.exists(pending)) return expectedId == null
        return try {
            val staged = FileHelper.readFileToBytes(pending)
            if (staged.size < 36 + 28) return false
            val id = String(staged, 0, 36, StandardCharsets.US_ASCII)
            val committed = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(TRANSACTION_ID, "") ?: ""
            if (expectedId != null && (id != expectedId || id != committed)) return false
            if (id == committed) {
                // Preferences committed first. Finish promotion after a crash/write failure.
                FileHelper.writeBytesToFile(file(context, Constants.FILENAME_DATABASE),
                    Arrays.copyOfRange(staged, 36, staged.size))
            }
            // An uncommitted staged write is not authoritative; keep the old vault.
            FileHelper.delete(pending)
            true
        } catch (error: IOException) {
            false
        } catch (error: RuntimeException) {
            false
        }
    }

    @JvmStatic
    fun readVault(context: Context, key: SecretKey?): VaultReadResult {
        synchronized(DatabaseFileLock) {
            if (!recoverPendingDatabase(context)) return unavailable(VaultReadError.RECOVERY_REQUIRED)
            val database = file(context, Constants.FILENAME_DATABASE)
            if (!FileHelper.exists(database)) return unavailable(VaultReadError.MISSING)
            if (key == null) return unavailable(VaultReadError.MISSING_KEY)
            var plain: ByteArray? = null
            try {
                plain = EncryptionHelper.decrypt(key, FileHelper.readFileToBytes(database))
                return VaultReadResult.Loaded(stringToEntries(String(plain, StandardCharsets.UTF_8)))
            } catch (error: BadPaddingException) {
                return unavailable(VaultReadError.AUTHENTICATION_FAILED)
            } catch (error: IOException) {
                return unavailable(VaultReadError.IO_FAILURE)
            } catch (error: Exception) {
                return unavailable(VaultReadError.CORRUPT)
            } finally {
                if (plain != null) Arrays.fill(plain, 0.toByte())
            }
        }
    }

    @JvmStatic
    fun loadDatabaseResult(context: Context, key: SecretKey?): LoadResult =
        LoadResult.create(readVault(context, key))

    @JvmStatic
    fun loadDatabase(context: Context, key: SecretKey?): ArrayList<Entry> =
        loadDatabaseResult(context, key).requireEntries()

    @JvmStatic
    fun saveVault(context: Context, entries: ArrayList<Entry>?, key: SecretKey?): VaultWriteResult {
        synchronized(DatabaseFileLock) {
            val read = readVault(context, key)
            if (read is VaultReadResult.Unavailable) return readFailure(read)
            (read as VaultReadResult.Loaded).use { return writeEntries(context, entries, key) }
        }
    }

    /** Legacy boolean adapter: only fully acknowledged writes count as success. */
    @JvmStatic
    fun saveDatabase(context: Context, entries: ArrayList<Entry>?, key: SecretKey?): Boolean =
        saveVault(context, entries, key) is VaultWriteResult.Saved

    // Null entries are rejected inside entriesToString after the existing vault is verified.
    private fun encryptedEntries(entries: ArrayList<Entry>?, key: SecretKey?): ByteArray {
        if (key == null) throw IllegalArgumentException("Missing vault key")
        val plain = entriesToString(entries).toByteArray(StandardCharsets.UTF_8)
        try {
            // Leave room for nonce/tag plus the 36-byte staged transaction UUID.
            if (plain.size > BoundedInputReader.MAX_DOCUMENT_BYTES - 64)
                throw IllegalArgumentException("Vault too large")
            return EncryptionHelper.encrypt(key, plain)
        } finally {
            Arrays.fill(plain, 0.toByte())
        }
    }

    private fun writeEntries(context: Context, entries: ArrayList<Entry>?, key: SecretKey?): VaultWriteResult {
        return try {
            FileHelper.writeBytesToFile(file(context, Constants.FILENAME_DATABASE), encryptedEntries(entries, key))
            VaultWriteResult.Saved
        } catch (error: IllegalArgumentException) {
            rejected(VaultWriteError.INVALID_INPUT)
        } catch (error: VaultException) {
            rejected(VaultWriteError.INVALID_INPUT)
        } catch (error: Exception) {
            rejected(VaultWriteError.IO_FAILURE)
        }
    }

    /** Legacy metadata callback: false means UNKNOWN, not guaranteed rollback. */
    fun interface SettingsCommit {
        fun commit(transactionId: String): Boolean
    }

    fun interface VaultSettingsCommit {
        fun commit(transactionId: String): VaultCommitDecision
    }

    @JvmStatic
    fun changeEncryption(context: Context, oldKey: SecretKey?, newKey: SecretKey?, initialize: Boolean,
                         commit: SettingsCommit): Boolean {
        return changeVaultEncryption(context, oldKey, newKey, initialize) { id ->
            try {
                VaultCommitDecision.fromCommit(commit.commit(id))
            } catch (canceled: CancellationException) {
                VaultCommitDecision.CANCELED
            }
        } is VaultWriteResult.Saved
    }

    /** Ciphertext -> explicit metadata decision/UUID -> promotion; ambiguous commits quarantine writes. */
    @JvmStatic
    fun changeVaultEncryption(context: Context, oldKey: SecretKey?, newKey: SecretKey?, initialize: Boolean,
                              commit: VaultSettingsCommit): VaultWriteResult {
        synchronized(DatabaseFileLock) {
            if (!recoverPendingDatabase(context)) return recoveryRequired(VaultCommitState.UNKNOWN)
            if (newKey == null) return rejected(VaultWriteError.MISSING_KEY)
            var entries: ArrayList<Entry>
            if (initialize) {
                if (FileHelper.exists(file(context, Constants.FILENAME_DATABASE))
                    || FileHelper.exists(file(context, Constants.FILENAME_DATABASE_BACKUP)))
                    return rejected(VaultWriteError.ALREADY_EXISTS)
                entries = ArrayList()
            } else {
                val read = readVault(context, oldKey)
                if (read is VaultReadResult.Unavailable) return readFailure(read)
                entries = (read as VaultReadResult.Loaded).takeEntries()
                if (!backupDatabase(context)) {
                    for (entry in entries) entry.destroy()
                    return rejected(VaultWriteError.BACKUP_FAILED)
                }
            }
            try {
                val encrypted = encryptedEntries(entries, newKey)
                val id = UUID.randomUUID().toString()
                val staged = ByteArray(36 + encrypted.size)
                System.arraycopy(id.toByteArray(StandardCharsets.US_ASCII), 0, staged, 0, 36)
                System.arraycopy(encrypted, 0, staged, 36, encrypted.size)
                FileHelper.writeBytesToFile(file(context, PENDING_FILE), staged)
                val decision = try {
                    metadataCommitInProgress = true
                    commit.commit(id)
                } catch (unknown: Exception) {
                    VaultCommitDecision.UNKNOWN
                } finally {
                    metadataCommitInProgress = false
                }
                val marker = try {
                    PreferenceManager.getDefaultSharedPreferences(context).getString(TRANSACTION_ID, "")
                } catch (unknown: RuntimeException) {
                    transactionPoisoned = true
                    return recoveryRequired(VaultCommitState.UNKNOWN)
                }
                if (decision == VaultCommitDecision.CANCELED && id != marker) {
                    // Contract: CANCELED must be returned before any metadata mutation.
                    return rejected(VaultWriteError.CANCELED)
                }
                if (decision != VaultCommitDecision.COMMITTED || id != marker) {
                    transactionPoisoned = true
                    return recoveryRequired(VaultCommitState.UNKNOWN)
                }
                return if (recoverPendingDatabaseLocked(context, id)) VaultWriteResult.Saved
                else recoveryRequired(VaultCommitState.COMMITTED)
            } catch (error: IllegalArgumentException) {
                return rejected(VaultWriteError.INVALID_INPUT)
            } catch (error: VaultException) {
                return rejected(VaultWriteError.INVALID_INPUT)
            } catch (error: Exception) {
                return rejected(VaultWriteError.IO_FAILURE)
            } finally {
                for (entry in entries) entry.destroy()
            }
        }
    }

    @JvmStatic
    fun canInitialize(context: Context): Boolean {
        synchronized(DatabaseFileLock) {
            return recoverPendingDatabase(context)
                && !FileHelper.exists(file(context, Constants.FILENAME_DATABASE))
                && !FileHelper.exists(file(context, Constants.FILENAME_DATABASE_BACKUP))
        }
    }

    @JvmStatic
    fun backupDatabase(context: Context): Boolean {
        synchronized(DatabaseFileLock) {
            return try {
                FileHelper.writeBytesToFile(file(context, Constants.FILENAME_DATABASE_BACKUP),
                    FileHelper.readFileToBytes(file(context, Constants.FILENAME_DATABASE)))
                true
            } catch (error: IOException) {
                false
            } catch (error: RuntimeException) {
                false
            }
        }
    }

    @JvmStatic
    fun restoreDatabaseBackup(context: Context): Boolean {
        synchronized(DatabaseFileLock) {
            if (!recoverPendingDatabase(context)) return false
            return try {
                FileHelper.writeBytesToFile(file(context, Constants.FILENAME_DATABASE),
                    FileHelper.readFileToBytes(file(context, Constants.FILENAME_DATABASE_BACKUP)))
                true
            } catch (error: IOException) {
                false
            } catch (error: RuntimeException) {
                false
            }
        }
    }

    /** Explicit destructive operation, serialized with migration and backup operations. */
    @JvmStatic
    fun wipeDatabase(context: Context) {
        VaultSession.lock(SessionLockReason.RESET)
        synchronized(DatabaseFileLock) {
            // Invalidate the shortcut even if a subsequent file deletion fails partway through.
            HardwareUnlockStore.clearAfterReset(context)
            for (name in arrayOf(Constants.FILENAME_DATABASE, Constants.FILENAME_DATABASE_BACKUP, PENDING_FILE)) {
                FileHelper.delete(file(context, name))
            }
            try { PendingAutomaticBackup.clear(context) } catch (_: RuntimeException) { }
            transactionPoisoned = false
        }
    }

    /** Bounded all-or-nothing writer: any per-entry failure becomes a corrupt-vault result. The
     *  portable encoding lives in core [VaultJson]; only the legacy exception translation stays here. */
    @JvmStatic
    fun entriesToString(entries: ArrayList<Entry>?): String =
        try {
            VaultJson.entriesToString(entries)
        } catch (error: VaultJson.MalformedVaultException) {
            throw VaultException.create(Status.CORRUPT)
        }

    /** All-or-nothing parser. Never return a prefix of a malformed backup. Delegates to core [VaultJson]. */
    @JvmStatic
    fun stringToEntries(data: String?): ArrayList<Entry> =
        try {
            VaultJson.stringToEntries(data)
        } catch (error: VaultJson.MalformedVaultException) {
            throw VaultException.create(Status.CORRUPT)
        }
}
