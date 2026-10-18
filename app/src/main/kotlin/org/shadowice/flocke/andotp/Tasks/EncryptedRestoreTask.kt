package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.net.Uri

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupHeader
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.StorageAccessHelper

import java.nio.charset.StandardCharsets

import javax.crypto.SecretKey

/** Reads a password-encrypted backup, in the current or in the legacy format. */
open class EncryptedRestoreTask(context: Context, uri: Uri?, private val password: String, private val oldFormat: Boolean) :
        GenericRestoreTask(context, uri) {

    protected override fun doInBackground(): BackupTaskResult {
        var success = true
        var decryptedString = ""

        try {
            val data = StorageAccessHelper.loadFile(applicationContext, uri)

            decryptedString = if (oldFormat) {
                // Legacy format: AES-GCM data only, key derived as SHA-256 of the password
                val key: SecretKey = EncryptionHelper.generateSymmetricKeyFromPassword(password)
                val decrypted = EncryptionHelper.decrypt(key, data)
                String(decrypted, StandardCharsets.UTF_8)
            } else {
                // Current format: [iteration count][salt][nonce + ciphertext]
                val header = EncryptedBackupHeader.parse(data)
                val key: SecretKey = EncryptionHelper.generateSymmetricKeyPBKDF2(
                        password, header.getIterations(), header.getSalt())

                val decrypted = EncryptionHelper.decrypt(key, header.getEncrypted())
                String(decrypted, StandardCharsets.UTF_8)
            }
        } catch (error: Exception) {
            success = false
            error.printStackTrace()
        }

        if (success) {
            return BackupTaskResult.success(BackupTaskResult.ResultType.RESTORE, decryptedString)
        } else {
            return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_decryption_failed)
        }
    }
}
