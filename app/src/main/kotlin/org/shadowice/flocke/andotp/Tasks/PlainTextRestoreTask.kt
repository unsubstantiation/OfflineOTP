package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.net.Uri

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.PlainTextBackupDecoder
import org.shadowice.flocke.andotp.Utilities.StorageAccessHelper

import java.io.IOException

/** Reads a legacy, unencrypted JSON backup through SAF; there is deliberately no plain-text writer. */
open class PlainTextRestoreTask(context: Context, uri: Uri?) : GenericRestoreTask(context, uri) {
    protected override fun doInBackground(): BackupTaskResult {
        var bytes: ByteArray? = null
        return try {
            if (isCanceled()) return BackupTaskResult.failure(
                BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_failed)
            bytes = StorageAccessHelper.loadFile(applicationContext, uri)
            if (isCanceled()) return BackupTaskResult.failure(
                BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_failed)
            BackupTaskResult.success(BackupTaskResult.ResultType.RESTORE,
                PlainTextBackupDecoder.decode(bytes))
        } catch (invalid: IllegalArgumentException) {
            BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE,
                R.string.backup_toast_plaintext_invalid)
        } catch (error: IOException) {
            BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE,
                R.string.backup_toast_import_failed)
        } catch (error: RuntimeException) {
            BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE,
                R.string.backup_toast_import_failed)
        } finally {
            bytes?.fill(0)
        }
    }
}
