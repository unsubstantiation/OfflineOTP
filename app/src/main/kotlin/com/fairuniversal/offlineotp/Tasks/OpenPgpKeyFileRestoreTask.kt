package com.fairuniversal.offlineotp.Tasks

import android.content.Context
import android.net.Uri

import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.OpenPgpBackupCodec
import com.fairuniversal.offlineotp.Utilities.OpenPgpKeyFilePolicy
import com.fairuniversal.offlineotp.Utilities.OpenPgpPort
import com.fairuniversal.offlineotp.Utilities.StorageAccessHelper

/**
 * Restores an OpenPGP backup decrypted in this app, with no external provider involved.
 *
 * The secret key is read from the user's selected document; [passphrase] unlocks it and is owned by
 * this task, which clears it on every exit path. It is never written to preferences, logs or the
 * result. A wrong key, a wrong passphrase and a corrupt message are reported identically so the
 * toast does not become an oracle.
 */
internal class OpenPgpKeyFileRestoreTask(
    context: Context,
    uri: Uri?,
    private val secretKeyUri: String,
    private val passphrase: CharArray?,
    private val port: OpenPgpPort
) : GenericRestoreTask(context, uri) {

    private fun clearPassphrase() {
        passphrase?.fill('\u0000')
    }

    protected override fun onCancellation() { clearPassphrase() }
    protected override fun discardResult(result: BackupTaskResult) { clearPassphrase() }

    protected override fun doInBackground(): BackupTaskResult {
        var secretKey: ByteArray? = null
        try {
            if (isCanceled() || !OpenPgpKeyFilePolicy.isUsableKeyUri(secretKeyUri)) return failure()
            val message = StorageAccessHelper.loadFile(applicationContext, uri)
            if (isCanceled()) return failure()
            secretKey = StorageAccessHelper.loadFile(applicationContext, Uri.parse(secretKeyUri))
            if (secretKey.isEmpty() || isCanceled()) return failure()

            val document = OpenPgpBackupCodec.open(port, message, secretKey, passphrase)
            if (isCanceled()) return failure()
            return BackupTaskResult.success(BackupTaskResult.ResultType.RESTORE, document)
        } catch (error: Exception) {
            return failure()
        } finally {
            secretKey?.fill(0)
            clearPassphrase()
        }
    }

    private fun failure(): BackupTaskResult = BackupTaskResult.failure(
        BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_decryption_failed)
}
