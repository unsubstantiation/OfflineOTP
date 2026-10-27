package org.shadowice.flocke.andotp.Tasks

import android.content.Context

import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import org.shadowice.flocke.andotp.Utilities.LoginBackupKey
import org.shadowice.flocke.andotp.Utilities.PendingAutomaticBackup
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultCommitDecision
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult

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
            if (authMethod == Constants.AuthMethod.PASSWORD || authMethod == Constants.AuthMethod.PIN) prepared = settings.prepareAuthCredentials(password)
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
