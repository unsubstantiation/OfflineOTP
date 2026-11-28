package com.fairuniversal.offlineotp.Tasks

import android.content.Context
import com.fairuniversal.offlineotp.domain.RestoreWorkflow
import com.fairuniversal.offlineotp.Utilities.VaultSession
import android.net.Uri
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.DatabaseHelper
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupCodec
import com.fairuniversal.offlineotp.Utilities.StorageAccessHelper
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import java.util.ArrayList

/** Decrypts and validates a portable backup before the first vault is created. */
class FirstRunBackupRestoreTask @JvmOverloads constructor(context: Context, private val uri: Uri, backupPassword: String,
                                private val format: String? = null, private val andOtpVariant: String = "encrypted") :
    UiBasedBackgroundTask<FirstRunBackupRestoreTask.Result>(Result(null)) {

    private val applicationContext: Context = context.getApplicationContext()
    private var password: String? = backupPassword

    class Result(var entries: ArrayList<Entry>?)
    protected override fun onCancellation() { password = null }

    protected override fun doInBackground(): Result {
        val workflow = RestoreWorkflow()
        val ticket = workflow.start(taskGeneration(), true)
        val secret = password.also { password = null } ?: return Result(null)
        var bytes: ByteArray? = null
        var entries: ArrayList<Entry>? = null
        try {
            if (isCanceled()) return Result(null)
            bytes = StorageAccessHelper.loadFile(applicationContext, uri)
            if (isCanceled()) return Result(null)
            val document = if (format == null) EncryptedBackupCodec.open(secret, bytes)
                else EncryptedBackupCodec.openSelected(secret, bytes, format, andOtpVariant)
            entries = DatabaseHelper.stringToEntries(document)
            if (!workflow.decoded(ticket, VaultSession.generation(), !isCanceled(), entries.size, entries.toHashSet().size == entries.size)) {
                destroy(entries); entries = null
                return Result(null)
            }
            val accepted = entries
            entries = null
            return Result(accepted)
        } catch (_: Exception) {
            entries?.let(::destroy)
            return Result(null)
        } finally {
            workflow.cancel()
            bytes?.fill(0)
        }
    }

    protected override fun discardResult(result: Result) { result.entries?.let(::destroy) }

    private fun destroy(entries: ArrayList<Entry>) {
        entries.forEach { it.destroy() }
        entries.clear()
    }
}
