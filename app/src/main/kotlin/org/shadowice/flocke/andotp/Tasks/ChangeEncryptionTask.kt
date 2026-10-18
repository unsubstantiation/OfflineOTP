package org.shadowice.flocke.andotp.Tasks

import android.content.Context

import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper.EncryptionChangeCallback
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper.EncryptionChangeResult

import javax.crypto.SecretKey

/** Re-encrypts the database after the encryption type was changed in the settings. */
open class ChangeEncryptionTask(context: Context, private val oldEncryptionKey: SecretKey?,
                                private val newEncryptionType: Constants.EncryptionType, newKey: ByteArray?) :
        UiBasedBackgroundTask<ChangeEncryptionTask.Result>(Result(EncryptionChangeResult.TASK_CREATION_FAILED, null, null)),
        EncryptionChangeCallback {

    private val context: Context = context.getApplicationContext()
    private val newKeyMaterial: ByteArray? = newKey?.clone()

    private var newEncryptionKey: SecretKey? = null

    protected override fun onCancellation() {
        newKeyMaterial?.fill(0.toByte())
    }

    override fun onSuccessfulEncryptionChange(newEncryptionType: Constants.EncryptionType, newEncryptionKey: SecretKey) {
        this.newEncryptionKey = newEncryptionKey
    }

    /** Runs the re-encryption with the given key material. */
    protected override fun doInBackground(): Result {
        try {
            if (isCanceled()) return Result(EncryptionChangeResult.CHANGE_FAILURE, null, this.newEncryptionType)
            val result = EncryptionHelper.tryEncryptionChange(context, oldEncryptionKey, newEncryptionType, newKeyMaterial, this) { !isCanceled() }
            return Result(result, this.newEncryptionKey, this.newEncryptionType)
        } finally {
            newKeyMaterial?.fill(0.toByte())
        }
    }

    /** Detailed outcome of the change (see [EncryptionChangeResult]) plus the new key and type. */
    open class Result(@JvmField val result: EncryptionChangeResult,
                      @JvmField val newEncryptionKey: SecretKey?,
                      @JvmField val encryptionType: Constants.EncryptionType?)
}
