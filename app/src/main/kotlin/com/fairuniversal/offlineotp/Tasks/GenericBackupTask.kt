package com.fairuniversal.offlineotp.Tasks

import android.content.Context
import android.net.Uri

import androidx.documentfile.provider.DocumentFile

import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.BackupHelper
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.Utilities.StorageAccessHelper
import com.fairuniversal.offlineotp.Utilities.VaultSession

/** Base for backup tasks: resolves the target file, then delegates to [doBackup]. */
abstract class GenericBackupTask(context: Context, uri: Uri?) :
        UiBasedBackgroundTask<BackupTaskResult>(BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, R.string.backup_toast_export_failed)) {

    protected val applicationContext: Context = context.getApplicationContext()
    protected val settings: Settings = Settings(applicationContext)
    /** The backup type written (used for the file name and MIME type). */
    protected val type: Constants.BackupType = getBackupType()
    /** Target file; resolved in [doInBackground] when null. */
    protected var uri: Uri? = uri

    /** Picks or creates the target file and then runs [doBackup]. */
    protected override fun doInBackground(): BackupTaskResult {
        var created: DocumentFile? = null
        var success = false
        try {
            if (!canWrite()) return BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, R.string.backup_toast_export_failed)
            val fileName: String
            if (uri == null) {
                val backup = BackupHelper.backupFile(applicationContext, settings.getBackupLocation(), type)
                val createdFile = backup.file
                if (createdFile == null) return BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, backup.errorMessage)
                created = createdFile
                uri = createdFile.getUri()
                fileName = StorageAccessHelper.getContentFileName(applicationContext, uri)
            } else fileName = StorageAccessHelper.getContentFileName(applicationContext, uri)
            success = canWrite() && doBackup() && canWrite()
            return if (success) BackupTaskResult.success(BackupTaskResult.ResultType.BACKUP, fileName)
            else BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, R.string.backup_toast_export_failed)
        } catch (error: RuntimeException) {
            return BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, R.string.backup_toast_export_failed)
        } finally {
            if (!success && created != null) {
                try { created.delete() } catch (ignored: RuntimeException) { }
            }
        }
    }

    /** The backup type this task writes (used for the file name and MIME type). */
    protected abstract fun getBackupType(): Constants.BackupType

    /** Writes the backup to [uri] and reports whether it succeeded. */
    protected abstract fun doBackup(): Boolean

    /** Whether the task is still allowed to write (not canceled and vault session unlocked). */
    protected open fun canWrite(): Boolean {
        return !isCanceled() && VaultSession.getKey() != null
    }
}
