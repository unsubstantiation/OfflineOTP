package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupTask
import org.shadowice.flocke.andotp.Tasks.LoginEncryptedBackupTask
import org.shadowice.flocke.andotp.Tasks.TaskFailure
import org.shadowice.flocke.andotp.Tasks.TaskOutcome
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import javax.crypto.SecretKey

/** Session-authorized entry I/O and change-triggered automatic backups. */
class EntryPersistence(context: Context) {
    private val context: Context = context.getApplicationContext()
    private val settings: Settings = Settings(this.context)

    private fun authorized(key: SecretKey?): Boolean = key != null && VaultSession.getKey() === key

    fun load(key: SecretKey?): VaultReadResult = synchronized(VaultSession.MutationLock) {
        if (!authorized(key)) return@synchronized VaultReadResult.Unavailable(VaultReadError.MISSING_KEY)
        DatabaseHelper.readVault(context, key!!)
    }

    fun save(entries: ArrayList<Entry>?, key: SecretKey?, autoBackup: Boolean,
            backupCallback: UiBasedBackgroundTask.OutcomeCallback<BackupTaskResult>?): VaultWriteResult {
        synchronized(VaultSession.MutationLock) {
            if (!authorized(key)) return VaultWriteResult.Rejected(VaultWriteError.MISSING_KEY)
            if (entries == null) return VaultWriteResult.Rejected(VaultWriteError.INVALID_INPUT)
            if (!autoBackup || !settings.getAutoBackupEncryptedPasswordsEnabled())
                return DatabaseHelper.saveVault(context, entries, key!!)

            // Compare canonical entry data rather than randomized vault ciphertext. If the vault
            // cannot be read, the existing save path will refuse without creating a snapshot.
            val loaded = DatabaseHelper.readVault(context, key!!)
            if (loaded !is VaultReadResult.Loaded) return DatabaseHelper.saveVault(context, entries, key)
            val oldEntries = loaded.takeEntries()
            val before: String
            val after: String
            try {
                before = DatabaseHelper.entriesToString(oldEntries)
                after = DatabaseHelper.entriesToString(entries)
            } catch (_: RuntimeException) {
                return VaultWriteResult.Rejected(VaultWriteError.INVALID_INPUT)
            } finally {
                oldEntries.forEach { it.destroy() }
            }
            if (before == after) {
                retryPending(key, backupCallback)
                return VaultWriteResult.Saved
            }

            // Mark before the atomic vault write. On a crash the keyed digest distinguishes a
            // committed change from an interrupted write that left the old vault untouched.
            val stage = try {
                PendingAutomaticBackup.stage(context, PendingAutomaticBackup.fingerprint(before, key))
            } catch (_: RuntimeException) {
                // Do not block the vault write, but never claim an automatic backup was scheduled.
                reportFailure(backupCallback)
                return DatabaseHelper.saveVault(context, entries, key)
            }
            val result = DatabaseHelper.saveVault(context, entries, key)
            if (result !is VaultWriteResult.Saved) {
                if (result is VaultWriteResult.Rejected) {
                    try { PendingAutomaticBackup.rollback(context, stage) } catch (_: RuntimeException) { }
                }
                return result
            }
            if (!stage.durable) reportOutcome(backupCallback, TaskOutcome.Value(
                BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, R.string.backup_toast_auto_retry_not_saved)))
            schedule(entries, stage.marker, backupCallback)
            return result
        }
    }

    /** Retry a committed but unfinished change after the next password/session unlock. */
    fun retryPending(key: SecretKey?, callback: UiBasedBackgroundTask.OutcomeCallback<BackupTaskResult>?) {
        synchronized(VaultSession.MutationLock) {
            try {
                if (!authorized(key) || !settings.getAutoBackupEncryptedPasswordsEnabled()) return
                val marker = PendingAutomaticBackup.read(context) ?: return
                val loaded = DatabaseHelper.readVault(context, key!!)
                if (loaded !is VaultReadResult.Loaded) return
                val entries = loaded.takeEntries()
                try {
                    val digest = PendingAutomaticBackup.fingerprint(DatabaseHelper.entriesToString(entries), key)
                    if (PendingAutomaticBackup.canDropReverted(context, marker, digest)) {
                        PendingAutomaticBackup.complete(context, marker.token)
                        return
                    }
                    schedule(entries, marker, callback)
                } finally {
                    entries.forEach { it.destroy() }
                }
            } catch (_: RuntimeException) {
                reportFailure(callback)
            }
        }
    }

    private fun reportFailure(callback: UiBasedBackgroundTask.OutcomeCallback<BackupTaskResult>?) {
        reportOutcome(callback, TaskOutcome.Failure(TaskFailure.UNEXPECTED_EXCEPTION))
    }

    /** A UI notification must never change the result of a vault write or interrupt retry cleanup. */
    private fun reportOutcome(callback: UiBasedBackgroundTask.OutcomeCallback<BackupTaskResult>?,
                              outcome: TaskOutcome<BackupTaskResult>) {
        try { callback?.onOutcome(outcome) } catch (_: RuntimeException) { }
    }

    private fun schedule(entries: ArrayList<Entry>, marker: PendingAutomaticBackup.Marker,
                         callback: UiBasedBackgroundTask.OutcomeCallback<BackupTaskResult>?) {
        val claimed = try { PendingAutomaticBackup.claim(context, marker.token) } catch (_: RuntimeException) {
            reportFailure(callback)
            return
        }
        if (!claimed) return
        val finish: (Boolean) -> Unit = { success ->
            try {
                if (success) PendingAutomaticBackup.complete(context, marker.token)
            } finally {
                PendingAutomaticBackup.release(marker.token)
            }
        }
        var lease: LoginBackupLease? = null
        var scheduled: UiBasedBackgroundTask<BackupTaskResult>? = null
        try {
            if (BackupHelper.autoBackupType(context) != Constants.BackupType.ENCRYPTED) {
                finish(false)
                // A hardware-only unlock cannot derive the app-password backup key. Keep the
                // marker for the next password unlock and explain why no backup was written.
                val loginConfigured = LoginBackupPolicy.usesLoginPassword(context) &&
                    settings.getAuthMethod() == Constants.AuthMethod.PASSWORD && settings.isBackupLocationSet()
                val availableKey = if (loginConfigured) VaultSession.copyBackupKey() else null
                val awaitingPassword = loginConfigured && availableKey == null
                availableKey?.close()
                if (awaitingPassword) reportOutcome(callback, TaskOutcome.Value(
                    BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, R.string.backup_toast_wait_password)))
                else reportFailure(callback)
                return
            }
            val task: UiBasedBackgroundTask<BackupTaskResult> = if (LoginBackupPolicy.usesLoginPassword(context)) {
                lease = VaultSession.copyBackupKey() ?: run {
                    finish(false)
                    reportFailure(callback)
                    return
                }
                LoginEncryptedBackupTask(context, entries, lease, finish).also { lease = null }
            } else {
                TrackedLegacyBackupTask(context, entries, settings.getBackupPasswordEnc(), finish)
            }
            scheduled = task
            // A callback is always installed so the task releases its session listener on delivery.
            task.setOutcomeCallback { outcome -> reportOutcome(callback, outcome) }
            task.execute()
        } catch (_: RuntimeException) {
            try { scheduled?.cancel() } catch (_: RuntimeException) { }
            try { lease?.close() } catch (_: RuntimeException) { }
            finish(false)
            reportFailure(callback)
        }
    }

    /** Preserves the existing separate-password path for users who have not opted into login backups. */
    private class TrackedLegacyBackupTask(context: Context, entries: ArrayList<Entry>, password: String,
                                          private val onFinished: (Boolean) -> Unit) :
        EncryptedBackupTask(context, entries, password, null, Mode.AUTO_EDIT, BooleanSupplier { true }) {
        private val started = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        private fun finish(success: Boolean) { if (finished.compareAndSet(false, true)) onFinished(success) }
        override fun onCancellation() {
            super.onCancellation()
            if (!started.get()) finish(false)
        }
        override fun doInBackground(): BackupTaskResult {
            started.set(true)
            return try {
                val result = super.doInBackground()
                finish(result.success)
                result
            } finally {
                finish(false)
            }
        }
    }
}
