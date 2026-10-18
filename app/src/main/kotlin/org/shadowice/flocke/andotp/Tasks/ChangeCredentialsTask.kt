package org.shadowice.flocke.andotp.Tasks

import android.content.Context

import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultCommitDecision
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult

import javax.crypto.SecretKey

/** Changes the authentication credentials and re-encrypts the database if required. */
open class ChangeCredentialsTask(context: Context, private val encryptionType: Constants.EncryptionType,
                                 private val oldEncryptionKey: SecretKey?, private val newAuthMethod: Constants.AuthMethod,
                                 private val password: String?) :
        UiBasedBackgroundTask<ChangeCredentialsTask.Result>(Result(false, null, null)) {

    private val context: Context = context.getApplicationContext()
    private val settings: Settings = Settings(this.context)

    /** Stores the new credentials and re-encrypts the database when password encryption is used. */
    protected override fun doInBackground(): Result {
        var prepared: Settings.PreparedCredentials? = null
        try {
            if (isCanceled() || oldEncryptionKey == null || VaultSession.getKey() !== oldEncryptionKey) return Result(false, null, null)
            if (newAuthMethod == Constants.AuthMethod.PASSWORD || newAuthMethod == Constants.AuthMethod.PIN) prepared = settings.prepareAuthCredentials(password)
            if (encryptionType == Constants.EncryptionType.PASSWORD && prepared == null) return Result(false, null, null)
            val nextKey: SecretKey = if (encryptionType == Constants.EncryptionType.PASSWORD)
                EncryptionHelper.generateSymmetricKey(prepared?.key ?: return Result(false, null, null))
            else oldEncryptionKey
            val credentials = prepared
            val epoch = VaultSession.generation()
            synchronized(DatabaseHelper.DatabaseFileLock) {
                if (isCanceled() || VaultSession.getKey() !== oldEncryptionKey) return Result(false, null, null)
                val write = DatabaseHelper.changeVaultEncryption(context, oldEncryptionKey, nextKey, false) { id ->
                    if (isCanceled() || !VaultSession.isCurrent(epoch)) VaultCommitDecision.CANCELED
                    else VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, encryptionType, newAuthMethod, credentials, false))
                }
                val success = write is VaultWriteResult.Saved
                return Result(success, if (success) nextKey else null, encryptionType)
            }
        } catch (error: Exception) {
            return Result(false, null, null)
        } finally {
            prepared?.destroy()
        }
    }

    /** Outcome of the change, including the new key and encryption type. */
    open class Result(@JvmField val success: Boolean,
                      @JvmField val newEncryptionKey: SecretKey?,
                      @JvmField val encryptionType: Constants.EncryptionType?)
}
