package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.content.Intent
import android.net.Uri

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.StorageAccessHelper

import java.io.IOException
import java.nio.charset.StandardCharsets

/** Reads an OpenPGP-encrypted backup document so the caller can pass it to the OpenPGP app. */
open class PGPRestoreTask(context: Context, uri: Uri?, private val decryptIntent: Intent) : GenericRestoreTask(context, uri) {

    protected override fun doInBackground(): BackupTaskResult {
        if (isCanceled()) {
            return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_failed)
        }
        try {
            val data = StorageAccessHelper.loadFile(applicationContext, uri)
            if (isCanceled()) {
                return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_failed)
            }
            return BackupTaskResult(BackupTaskResult.ResultType.RESTORE, true,
                    String(data, StandardCharsets.UTF_8), 0, true, decryptIntent, uri).apply {
                rawPayload = data
            }
        } catch (error: IOException) {
            return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_failed)
        } catch (error: RuntimeException) {
            return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_failed)
        }
    }
}
