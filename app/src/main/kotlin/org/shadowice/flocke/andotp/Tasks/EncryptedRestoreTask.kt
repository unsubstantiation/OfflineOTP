package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.net.Uri

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupCodec
import org.shadowice.flocke.andotp.Utilities.StorageAccessHelper

/** Reads a password-encrypted backup, in the current or in the legacy format. */
open class EncryptedRestoreTask(context: Context, uri: Uri?, private val password: String, private val oldFormat: Boolean) :
        GenericRestoreTask(context, uri) {

    protected override fun doInBackground(): BackupTaskResult {
        if (isCanceled()) {
            return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_decryption_failed)
        }
        var success = true
        var decryptedString = ""

        try {
            val data = StorageAccessHelper.loadFile(applicationContext, uri)
            if (isCanceled()) {
                return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_decryption_failed)
            }

            // Framing, key derivation (PBKDF2 current format; SHA-256 legacy) and AES-GCM all live
            // in the core codec; this task only owns document loading, cancellation and results.
            decryptedString = EncryptedBackupCodec.open(password, data, oldFormat)
        } catch (error: Exception) {
            success = false
            error.printStackTrace()
        }

        if (success && !isCanceled()) {
            return BackupTaskResult.success(BackupTaskResult.ResultType.RESTORE, decryptedString)
        } else {
            return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_decryption_failed)
        }
    }
}
