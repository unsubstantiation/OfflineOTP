package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.net.Uri
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.LoginBackupLease
import org.shadowice.flocke.andotp.Utilities.LoginBackupPolicy
import org.shadowice.flocke.andotp.Utilities.StorageAccessHelper
import org.shadowice.flocke.andotp.Utilities.VaultSession
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/** Snapshot encrypted with a session-owned login-derived key, never a stored password. */
internal class LoginEncryptedBackupTask(
    context: Context,
    source: ArrayList<Entry>,
    private val lease: LoginBackupLease,
    private val onFinished: (Boolean) -> Unit,
    uri: Uri? = null,
    private val automatic: Boolean = true
) : GenericBackupTask(context, uri) {
    private val configuredLocation = if (settings.isBackupLocationSet()) settings.getBackupLocation() else null
    private val entries = DatabaseHelper.stringToEntries(DatabaseHelper.entriesToString(source))
    private val started = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val backupChanged = Runnable { if (!VaultSession.isBackupCurrent(lease)) cancel() }

    init {
        VaultSession.addBackupChangeListener(backupChanged)
        // Close the registration race with a concurrent lock or credential replacement.
        if (!VaultSession.isBackupCurrent(lease)) cancel()
    }

    private fun finish(success: Boolean) {
        if (finished.compareAndSet(false, true)) onFinished(success)
    }

    private fun destroyOwned() {
        VaultSession.removeBackupChangeListener(backupChanged)
        synchronized(entries) {
            for (entry in entries) entry.destroy()
            entries.clear()
        }
        lease.close()
    }

    override fun onCancellation() {
        destroyOwned()
        if (!started.get()) finish(false)
    }
    override fun discardResult(result: BackupTaskResult) = destroyOwned()

    override fun canWrite(): Boolean {
        if (isCanceled() || !VaultSession.isBackupCurrent(lease) ||
                settings.getAuthMethod() != Constants.AuthMethod.PASSWORD ||
                !LoginBackupPolicy.usesLoginPassword(applicationContext)) return false
        if (!automatic) return true
        return settings.getAutoBackupEncryptedPasswordsEnabled() && configuredLocation != null &&
            settings.isBackupLocationSet() && configuredLocation == settings.getBackupLocation()
    }

    override fun doInBackground(): BackupTaskResult {
        started.set(true)
        return try {
            val result = super.doInBackground()
            finish(result.success)
            result
        } finally {
            destroyOwned()
            finish(false)
        }
    }

    override fun getBackupType(): Constants.BackupType = Constants.BackupType.ENCRYPTED

    override fun doBackup(): Boolean {
        val plaintext = synchronized(entries) { DatabaseHelper.entriesToString(entries).toByteArray(StandardCharsets.UTF_8) }
        try {
            val ciphertext = lease.key.encrypt(plaintext)
            return StorageAccessHelper.saveFile(applicationContext, uri, ciphertext, BooleanSupplier { canWrite() })
        } finally {
            plaintext.fill(0)
        }
    }
}
