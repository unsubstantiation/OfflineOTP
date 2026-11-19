package com.fairuniversal.offlineotp.Tasks

import android.content.Context
import android.net.Uri
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.DatabaseHelper
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupCodec
import com.fairuniversal.offlineotp.Utilities.StorageAccessHelper
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import java.util.ArrayList

/** Decrypts and validates a portable backup before the first vault is created. */
class FirstRunBackupRestoreTask(context: Context, private val uri: Uri, backupPassword: String) :
    UiBasedBackgroundTask<FirstRunBackupRestoreTask.Result>(Result(null)) {

    private val applicationContext: Context = context.getApplicationContext()
    private var password: String? = backupPassword

    class Result(var entries: ArrayList<Entry>?)

    protected override fun doInBackground(): Result {
        val secret = password.also { password = null } ?: return Result(null)
        var bytes: ByteArray? = null
        var entries: ArrayList<Entry>? = null
        try {
            if (isCanceled()) return Result(null)
            bytes = StorageAccessHelper.loadFile(applicationContext, uri)
            if (isCanceled()) return Result(null)
            val document = EncryptedBackupCodec.open(secret, bytes)
            entries = DatabaseHelper.stringToEntries(document)
            if (entries.size > InputPolicy.MAX_ENTRIES || entries.toHashSet().size != entries.size) {
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
            bytes?.fill(0)
        }
    }

    protected override fun discardResult(result: Result) { result.entries?.let(::destroy) }

    private fun destroy(entries: ArrayList<Entry>) {
        entries.forEach { it.destroy() }
        entries.clear()
    }
}
