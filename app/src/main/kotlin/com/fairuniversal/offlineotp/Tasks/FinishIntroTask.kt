package com.fairuniversal.offlineotp.Tasks

import android.content.Context

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.DatabaseHelper
import com.fairuniversal.offlineotp.Utilities.EncryptionHelper
import com.fairuniversal.offlineotp.Utilities.KeyStoreHelper
import com.fairuniversal.offlineotp.Utilities.LoginBackupKey
import com.fairuniversal.offlineotp.Utilities.PendingAutomaticBackup
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.Utilities.VaultCommitDecision
import com.fairuniversal.offlineotp.Utilities.VaultSession
import com.fairuniversal.offlineotp.Utilities.VaultWriteResult

import javax.crypto.SecretKey

/** Persists the choices made in the intro screen and derives the initial encryption key. */
open class FinishIntroTask(context: Context, private val encryptionType: Constants.EncryptionType,
                           private val authMethod: Constants.AuthMethod, private val password: String?,
                           private val androidSyncEnabled: Boolean) :
        UiBasedBackgroundTask<FinishIntroTask.Result>(Result(false, null)) {

    private val settings: Settings
    private val settingsContext: Context

    init {
        val applicationContext = context.getApplicationContext()
        settings = Settings(applicationContext)
        settingsContext = applicationContext
    }

    protected override fun doInBackground(): Result {
        var prepared: Settings.PreparedCredentials? = null
        try {
            if (isCanceled() || !DatabaseHelper.canInitialize(settingsContext)) return Result(false, null, taskGeneration())
            if (authMethod == Constants.AuthMethod.PASSWORD) prepared = settings.prepareAuthCredentials(password)
            if (encryptionType == Constants.EncryptionType.PASSWORD && prepared == null) return Result(false, null, taskGeneration())
            val key: SecretKey? = if (encryptionType == Constants.EncryptionType.PASSWORD)
                EncryptionHelper.generateSymmetricKey(prepared?.key ?: return Result(false, null, taskGeneration()))
            else KeyStoreHelper.createEncryptionKey(settingsContext)
            if (key == null) return Result(false, null, taskGeneration())
            val credentials = prepared
            val write = DatabaseHelper.changeVaultEncryption(settingsContext, null, key, true) { id ->
                if (isCanceled()) VaultCommitDecision.CANCELED
                else VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, encryptionType, authMethod, credentials, true))
            }
            val success = write is VaultWriteResult.Saved
            if (success) try { PendingAutomaticBackup.clear(settingsContext) } catch (_: RuntimeException) { }
            if (success && authMethod == Constants.AuthMethod.PASSWORD && password != null && !isCanceled()) {
                try {
                    LoginBackupKey.derive(password).use { candidate ->
                        if (!isCanceled()) VaultSession.offerBackupKey(candidate, taskGeneration())
                    }
                } catch (_: Exception) {
                    // Setup remains valid; password-based automatic backups require a later unlock.
                }
            }
            return Result(success, if (success) key.getEncoded() else null, taskGeneration())
        } catch (error: Exception) {
            return Result(false, null, taskGeneration())
        } finally {
            prepared?.destroy()
        }
    }

    protected override fun discardResult(result: Result) {
        result.encryptionKey?.fill(0.toByte())
    }

    /** Whether the initial setup was saved, plus the new encryption key. */
    open class Result(saveSuccessful: Boolean, encryptionKey: ByteArray?, generation: Long) {
        @JvmField val generation: Long = generation
        @JvmField val saveSuccessful: Boolean = saveSuccessful
        @JvmField val encryptionKey: ByteArray? = encryptionKey

        constructor(saveSuccessful: Boolean, encryptionKey: ByteArray?) :
                this(saveSuccessful, encryptionKey, VaultSession.generation())
    }
}
