package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.net.Uri

import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.StorageAccessHelper

import java.nio.charset.StandardCharsets

/** Writes a backup whose contents were already encrypted by the OpenPGP app. */
open class PGPBackupTask(context: Context, private val payload: String, uri: Uri?) : GenericBackupTask(context, uri) {
    @JvmSynthetic
    internal var rawPayload: ByteArray? = null

    protected override fun getBackupType(): Constants.BackupType {
        return Constants.BackupType.OPEN_PGP
    }

    protected override fun doBackup(): Boolean {
        val bytes = rawPayload ?: payload.toByteArray(StandardCharsets.UTF_8)
        return StorageAccessHelper.saveFile(applicationContext, uri, bytes, this::canWrite)
    }
}
