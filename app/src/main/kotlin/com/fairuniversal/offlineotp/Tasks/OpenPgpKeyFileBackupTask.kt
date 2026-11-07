package com.fairuniversal.offlineotp.Tasks

import android.content.Context
import android.net.Uri

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.DatabaseHelper
import com.fairuniversal.offlineotp.Utilities.OpenPgpBackupCodec
import com.fairuniversal.offlineotp.Utilities.OpenPgpKeyFilePolicy
import com.fairuniversal.offlineotp.Utilities.OpenPgpPort
import com.fairuniversal.offlineotp.Utilities.StorageAccessHelper
import com.fairuniversal.offlineotp.Utilities.VaultSession

import java.util.ArrayList

/**
 * Writes an OpenPGP backup encrypted in this app, with no external provider involved.
 *
 * The recipient certificates are read from the user's selected documents on the background thread
 * and the shared [OpenPgpBackupCodec] enforces the same document bounds and empty-output rules as
 * the password backup path. Only the outer envelope differs from the provider flow, so a backup
 * written here restores in OpenKeychain and vice versa.
 *
 * Like [EncryptedBackupTask], this owns a validated snapshot of the entries: a UI edit or a lock
 * must not change an export that is already in flight, and the snapshot is destroyed on every exit
 * path.
 */
internal class OpenPgpKeyFileBackupTask(
    context: Context,
    entries: ArrayList<Entry>,
    recipientUris: List<String>,
    private val port: OpenPgpPort,
    uri: Uri?
) : GenericBackupTask(context, uri) {

    private val entries: ArrayList<Entry> =
        DatabaseHelper.stringToEntries(DatabaseHelper.entriesToString(entries))
    private val recipients: List<String> = OpenPgpKeyFilePolicy.recipients(recipientUris.toSet())

    private fun destroySnapshot() {
        synchronized(this.entries) {
            for (entry in this.entries) entry.destroy()
            this.entries.clear()
        }
    }

    protected override fun onCancellation() { destroySnapshot() }
    protected override fun discardResult(result: BackupTaskResult) { destroySnapshot() }

    protected override fun canWrite(): Boolean =
        !isCanceled() && VaultSession.getKey() != null && recipients.isNotEmpty()

    protected override fun doInBackground(): BackupTaskResult {
        try {
            return super.doInBackground()
        } finally {
            destroySnapshot()
        }
    }

    protected override fun getBackupType(): Constants.BackupType = Constants.BackupType.OPEN_PGP

    protected override fun doBackup(): Boolean {
        if (!canWrite()) return false
        val keyBlocks = ArrayList<ByteArray>()
        try {
            for (recipient in recipients) {
                if (!canWrite()) return false
                val block = StorageAccessHelper.loadFile(applicationContext, Uri.parse(recipient))
                // An empty or unreadable certificate must fail the backup, not silently drop a
                // recipient the user believes the backup was encrypted to.
                if (block.isEmpty()) return false
                keyBlocks.add(block)
            }
            if (!canWrite()) return false
            val plain = synchronized(entries) { DatabaseHelper.entriesToString(entries) }
            val message = OpenPgpBackupCodec.seal(port, plain, keyBlocks)
            return StorageAccessHelper.saveFile(applicationContext, uri, message, this::canWrite)
        } catch (error: Exception) {
            // Bad key material, a revoked session or a provider I/O failure all fail the backup.
            return false
        } finally {
            for (block in keyBlocks) block.fill(0)
        }
    }
}
