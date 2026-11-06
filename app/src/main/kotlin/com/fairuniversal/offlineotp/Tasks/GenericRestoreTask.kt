package com.fairuniversal.offlineotp.Tasks

import android.content.Context
import android.net.Uri

import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.Settings

/** Base for restore tasks, which read the given document in [doInBackground]. */
abstract class GenericRestoreTask(context: Context, uri: Uri?) :
        UiBasedBackgroundTask<BackupTaskResult>(BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_failed)) {

    protected val applicationContext: Context = context.getApplicationContext()
    protected val settings: Settings = Settings(applicationContext)
    protected var uri: Uri? = uri

    /** Reads the selected backup document on a background thread. */
    protected abstract override fun doInBackground(): BackupTaskResult
}
