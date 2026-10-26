package org.shadowice.flocke.andotp.Tasks

import android.content.Intent
import android.net.Uri

import org.shadowice.flocke.andotp.R

/**
 * Result of a backup or restore task, including the toast/message resource ID.
 *
 * Not a data class because [payload] can hold decrypted backup JSON and must not be exposed via
 * generated `toString`/`equals`/`copy` methods.
 */
open class BackupTaskResult(type: ResultType, success: Boolean, payload: String?, messageId: Int) {
    /** Whether the result belongs to a backup or a restore (NONE for neither). */
    enum class ResultType {
        NONE, BACKUP, RESTORE
    }

    @JvmField val resultType: ResultType = type
    @JvmField val success: Boolean = success
    @JvmField val payload: String? = payload
    @JvmField val messageId: Int = messageId

    @JvmField var isPGP: Boolean = false
    @JvmField var decryptIntent: Intent? = null
    @JvmField var uri: Uri? = null
    @JvmSynthetic internal var rawPayload: ByteArray? = null

    constructor(type: ResultType, success: Boolean, payload: String?, messageId: Int,
                isPGP: Boolean, decryptIntent: Intent?, uri: Uri?) : this(type, success, payload, messageId) {
        this.isPGP = isPGP
        this.decryptIntent = decryptIntent
        this.uri = uri
    }

    companion object {
        /** Reports a successful backup ([payload] is the file name) or restore ([payload] is the decrypted JSON). */
        @JvmStatic
        fun success(type: ResultType, payload: String?): BackupTaskResult {
            return BackupTaskResult(type, true, payload, R.string.backup_toast_export_success)
        }

        /** Reports a failed backup/restore; [messageId] is the error message resource ID (0 for default). */
        @JvmStatic
        fun failure(type: ResultType, messageId: Int): BackupTaskResult {
            return BackupTaskResult(type, false, null, messageId)
        }
    }
}
