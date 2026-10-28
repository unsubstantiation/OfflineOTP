package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.net.Uri

import androidx.documentfile.provider.DocumentFile

import org.shadowice.flocke.andotp.R

import java.util.UUID
import java.util.function.BooleanSupplier

/** Filenames, MIME types, and file I/O for encrypted and OpenPGP backups. */
object BackupHelper {
    /** Resolved backup target [file], or [errorMessage] resource ID if creation failed. */
    open class BackupFile {
        @JvmField var file: DocumentFile? = null
        @JvmField var errorMessage: Int = 0
    }

    private fun backupMimeType(type: Constants.BackupType): String = when (type) {
        Constants.BackupType.ENCRYPTED -> Constants.BACKUP_MIMETYPE_CRYPT
        Constants.BackupType.OPEN_PGP -> Constants.BACKUP_MIMETYPE_PGP
        else -> "application/octet-stream"
    }

    /** Finds (or creates) the backup file in the configured location. */
    @JvmStatic
    fun backupFile(context: Context, backupLocationUri: Uri, type: Constants.BackupType): BackupFile {
        val backupFile = BackupFile()
        val backupLocation = DocumentFile.fromTreeUri(context, backupLocationUri)

        if (backupLocation != null) {
            // Never truncate the last recoverable backup, even with timestamp naming disabled.
            val uniqueName = "otp_accounts_" + UUID.randomUUID() +
                    (if (type == Constants.BackupType.OPEN_PGP) ".json.gpg" else ".json.aes")
            backupFile.file = backupLocation.createFile(backupMimeType(type), uniqueName)

            // Neither an existing nor a new file is available
            if (backupFile.file == null)
                backupFile.errorMessage = R.string.backup_toast_file_creation_failed
        } else {
            backupFile.errorMessage = R.string.backup_toast_location_access_failed
        }

        return backupFile
    }

    /** The file name for the given backup type, optionally with a date/time suffix. */
    @JvmStatic
    fun backupFilename(context: Context, type: Constants.BackupType): String {
        val settings = Settings(context)
        return when (type) {
            Constants.BackupType.ENCRYPTED ->
                if (settings.getIsAppendingDateTimeToBackups())
                    String.format(Constants.BACKUP_FILENAME_CRYPT_FORMAT, Tools.getDateTimeString())
                else Constants.BACKUP_FILENAME_CRYPT
            Constants.BackupType.OPEN_PGP ->
                if (settings.getIsAppendingDateTimeToBackups())
                    String.format(Constants.BACKUP_FILENAME_PGP_FORMAT, Tools.getDateTimeString())
                else Constants.BACKUP_FILENAME_PGP
            else -> Constants.BACKUP_FILENAME_CRYPT
        }
    }

    /** Automatic backups need a location and an available key for the selected password mode. */
    @JvmStatic
    fun autoBackupType(context: Context): Constants.BackupType {
        val settings = Settings(context)
        if (!settings.isBackupLocationSet()) return Constants.BackupType.UNAVAILABLE
        if (LoginBackupPolicy.usesLoginPassword(context)) {
            if (settings.getAuthMethod() != Constants.AuthMethod.PASSWORD) return Constants.BackupType.UNAVAILABLE
            val lease = VaultSession.copyBackupKey() ?: return Constants.BackupType.UNAVAILABLE
            lease.close()
            return Constants.BackupType.ENCRYPTED
        }
        return if (settings.getBackupPasswordEnc().isNotEmpty()) Constants.BackupType.ENCRYPTED
        else Constants.BackupType.UNAVAILABLE
    }

    /** Writes a password-encrypted backup: iteration count + salt + nonce + AES-GCM ciphertext. */
    @JvmStatic
    fun backupToFile(context: Context, uri: Uri?, password: String?, plain: String): Boolean =
        backupToFile(context, uri, password, plain, BooleanSupplier { true })

    @JvmStatic
    fun backupToFile(context: Context, uri: Uri?, password: String?, plain: String,
                     permitted: BooleanSupplier): Boolean {
        return try {
            if (password == null || password.isEmpty() || !permitted.getAsBoolean()) return false
            // Framing and key derivation live in core EncryptedBackupCodec; only document I/O stays here.
            val data = EncryptedBackupCodec.seal(password, plain)
            StorageAccessHelper.saveFile(context, uri, data, permitted)
        } catch (error: Exception) {
            // Never log passwords or document contents.
            false
        }
    }
}
