package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.util.Log

import androidx.work.ListenableWorker.Result
import androidx.work.Worker
import androidx.work.WorkerParameters

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import org.shadowice.flocke.andotp.Utilities.LoginBackupPolicy
import org.shadowice.flocke.andotp.Utilities.PendingAutomaticBackup
import org.shadowice.flocke.andotp.Utilities.NotificationHelper
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultReadResult
import org.shadowice.flocke.andotp.Utilities.VaultSession

import java.util.ArrayList
import java.util.function.BooleanSupplier

import javax.crypto.SecretKey

/** WorkManager worker that retries a pending legacy password-encrypted backup. */
open class EncryptedBackupWorker(context: Context, params: WorkerParameters) :
        Worker(context, params) {

    companion object {
        private val TAG = EncryptedBackupWorker::class.java.simpleName
    }

    /** Subclass of [EncryptedBackupTask] that exposes its protected [doInBackground] for synchronous execution. */
    private class BroadcastBackupTask(
        context: Context,
        entries: ArrayList<Entry>,
        password: String?,
        permitted: BooleanSupplier
    ) : EncryptedBackupTask(context, entries, password, null, EncryptedBackupTask.Mode.BROADCAST, permitted) {
        fun run(): BackupTaskResult = doInBackground()
    }

    override fun doWork(): Result {
        val context = getApplicationContext()
        val settings = Settings(context)

        if (isStopped() || !settings.isEncryptedBackupBroadcastEnabled()) return Result.failure()
        // Login-derived credentials are never persisted for a detached worker. Such changes are
        // retried only when the user unlocks the vault with the app password.
        if (LoginBackupPolicy.usesLoginPassword(context) || !settings.getAutoBackupEncryptedPasswordsEnabled())
            return Result.failure()
        // Settings may have changed since the broadcast was received, so check again.
        // Never write a backup without a password.
        val password: String? = settings.getBackupPasswordEnc()
        if (password == null || password.isEmpty()) {
            return fail(context, R.string.backup_receiver_no_password_set)
        }
        if (!settings.isBackupLocationSet()) {
            return fail(context, R.string.backup_receiver_no_location_set)
        }
        if (settings.getEncryption() != Constants.EncryptionType.KEYSTORE) {
            return fail(context, R.string.backup_receiver_custom_encryption_failed)
        }

        try {
            val generation: Long
            val entries: ArrayList<Entry>
            val marker: PendingAutomaticBackup.Marker
            synchronized(DatabaseHelper.DatabaseFileLock) {
                if (isStopped() || !settings.isEncryptedBackupBroadcastEnabled()) return Result.failure()
                marker = PendingAutomaticBackup.read(context) ?: return Result.success()
                generation = VaultSession.generation()
                val encryptionKey: SecretKey? = KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, false)
                val loaded = DatabaseHelper.readVault(context, encryptionKey)
                if (loaded !is VaultReadResult.Loaded || encryptionKey == null)
                    return fail(context, R.string.backup_receiver_unexpected_error)
                entries = loaded.takeEntries()
                val digest = try {
                    PendingAutomaticBackup.fingerprint(DatabaseHelper.entriesToString(entries), encryptionKey)
                } catch (error: RuntimeException) {
                    entries.forEach { it.destroy() }
                    throw error
                }
                if (PendingAutomaticBackup.canDropReverted(context, marker, digest)) {
                    entries.forEach { it.destroy() }
                    PendingAutomaticBackup.complete(context, marker.token)
                    return Result.success()
                }
                if (!PendingAutomaticBackup.claim(context, marker.token)) {
                    entries.forEach { it.destroy() }
                    return Result.success()
                }
            }
            val result: BackupTaskResult
            try {
                result = BroadcastBackupTask(context, entries, password,
                        BooleanSupplier { !isStopped() && VaultSession.isCurrent(generation) }).run()
                if (result.success) PendingAutomaticBackup.complete(context, marker.token)
            } finally {
                for (entry in entries) entry.destroy()
                PendingAutomaticBackup.release(marker.token)
            }

            if (result.success) {
                NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_SUCCESS, R.string.backup_receiver_title_backup_success, result.payload)
                return Result.success()
            }
            if (isStopped()) return Result.failure()
            return fail(context, result.messageId)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Encrypted backup failed")
            return fail(context, R.string.backup_receiver_unexpected_error)
        }
    }

    /** Shows the failure notification. No retry: a failed backup would most likely fail again. */
    private fun fail(context: Context, messageId: Int): Result {
        val resolvedMessageId = if (messageId != 0) messageId else R.string.backup_receiver_unexpected_error
        NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, resolvedMessageId)
        return Result.failure()
    }
}
