package com.fairuniversal.offlineotp.Tasks

import android.content.Context
import android.net.Uri

import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupCodec
import com.fairuniversal.offlineotp.Utilities.StorageAccessHelper

/** Reads a password-encrypted backup. */
open class EncryptedRestoreTask @JvmOverloads constructor(context: Context, uri: Uri?, password: String,
                                private val format: String? = null, private val andOtpVariant: String = "encrypted") :
        GenericRestoreTask(context, uri) {

    private var password: String? = password
    protected override fun onCancellation() { password = null }

    protected override fun doInBackground(): BackupTaskResult {
        val secret = password.also { password = null }
            ?: return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_decryption_failed)
        if (isCanceled()) {
            return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_decryption_failed)
        }
        var success = true
        var decryptedString = ""

        try {
            val data = StorageAccessHelper.loadFile(applicationContext, uri)
            // Framing, PBKDF2 key derivation and AES-GCM all live in the core codec; this task
            // only owns document loading, cancellation and results.
            try {
                if (isCanceled()) return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_decryption_failed)
                decryptedString = if (format == null) EncryptedBackupCodec.open(secret, data)
                    else EncryptedBackupCodec.openSelected(secret, data, format, andOtpVariant)
            } finally { data.fill(0) }
        } catch (error: Exception) {
            success = false
        }

        if (success && !isCanceled()) {
            return BackupTaskResult.success(BackupTaskResult.ResultType.RESTORE, decryptedString)
        } else {
            return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_decryption_failed)
        }
    }
}
