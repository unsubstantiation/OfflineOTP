package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.net.Uri

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.BackupHelper
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.VaultSession

import java.util.ArrayList
import java.util.function.BooleanSupplier

/** Writes a password-encrypted backup file. */
open class EncryptedBackupTask : GenericBackupTask {
    enum class Mode { USER, AUTO_EDIT, BROADCAST }

    private val mode: Mode
    private val configuredLocation: Uri?
    private val permitted: BooleanSupplier
    private val password: String?
    private val entries: ArrayList<Entry>

    constructor(context: Context, entries: ArrayList<Entry>, password: String?, uri: Uri?) :
            this(context, entries, password, uri, Mode.USER, BooleanSupplier { true })

    constructor(context: Context, entries: ArrayList<Entry>, password: String?, uri: Uri?, mode: Mode,
                permitted: BooleanSupplier) : super(context, uri) {
        // Own a validated snapshot; UI mutations/lock cleanup must not alter an export in flight.
        this.entries = DatabaseHelper.stringToEntries(DatabaseHelper.entriesToString(entries))
        this.password = password
        this.mode = mode
        this.configuredLocation = if (settings.isBackupLocationSet()) settings.getBackupLocation() else null
        this.permitted = permitted
    }

    private fun destroySnapshot() {
        synchronized(entries) {
            for (entry in entries) entry.destroy()
            entries.clear()
        }
    }

    protected override fun onCancellation() { destroySnapshot() }
    protected override fun discardResult(result: BackupTaskResult) { destroySnapshot() }

    /* A broadcast backup is detached from the UI session: only an explicit cancel (or a stopped
     * worker) stops it, not a generation change caused by lock/unlock in the UI. */
    override fun isCanceled(): Boolean {
        return if (mode == Mode.BROADCAST) wasCanceled() else super.isCanceled()
    }

    protected override fun canWrite(): Boolean {
        if (isCanceled() || !permitted.getAsBoolean() || password == null || password.isEmpty()) return false
        if (mode == Mode.USER) return VaultSession.getKey() != null
        if (configuredLocation == null || !settings.isBackupLocationSet()
                || configuredLocation != settings.getBackupLocation() || password != settings.getBackupPasswordEnc()) return false
        if (mode == Mode.BROADCAST) return settings.isEncryptedBackupBroadcastEnabled()
                && settings.getEncryption() == Constants.EncryptionType.KEYSTORE
        return VaultSession.getKey() != null && settings.getAutoBackupEncryptedPasswordsEnabled()
    }

    protected override fun doInBackground(): BackupTaskResult {
        try {
            return super.doInBackground()
        } finally {
            destroySnapshot()
        }
    }

    protected override fun getBackupType(): Constants.BackupType {
        return Constants.BackupType.ENCRYPTED
    }

    protected override fun doBackup(): Boolean {
        val payload = synchronized(entries) { DatabaseHelper.entriesToString(entries) }
        return BackupHelper.backupToFile(applicationContext, uri, password, payload, this::canWrite)
    }
}
