/*
 * Copyright (C) 2018-2020 Jakob Nixdorf
 * Copyright (C) 2018-2020 Richy HBM
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Receivers

import android.content.Context
import android.content.Intent

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupWorker
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.LoginBackupPolicy
import org.shadowice.flocke.andotp.Utilities.PendingAutomaticBackup
import org.shadowice.flocke.andotp.Utilities.NotificationHelper
import org.shadowice.flocke.andotp.Utilities.Settings

/**
 * Queues a pending legacy automatic-backup retry when [ACTION] is received.
 *
 * Manifest signature permission `CONTROL_VAULT` restricts callers to same-signer apps.
 */
open class EncryptedBackupBroadcastReceiver : BackupBroadcastReceiver() {
    companion object {
        /** The unique work name of the queued backup. */
        const val UNIQUE_WORK_NAME = "encrypted_backup"

        /** The broadcast action this receiver answers to. */
        const val ACTION = "org.shadowice.flocke.andotp.broadcast.ENCRYPTED_BACKUP"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || !ACTION.equals(intent.getAction())) return
        val settings = Settings(context)

        if (!settings.isEncryptedBackupBroadcastEnabled()) {
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, R.string.backup_receiver_encrypted_disabled)
            return
        }

        if (!canSaveBackup(context)) {
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, R.string.backup_receiver_no_location_set)
            return
        }

        // Broadcasts can retry a real pending change, not manufacture unchanged snapshots. Login
        // backup keys exist only in the unlocked process and are retried there instead.
        if (LoginBackupPolicy.usesLoginPassword(context) || !settings.getAutoBackupEncryptedPasswordsEnabled() ||
                PendingAutomaticBackup.read(context) == null) return
        if (!settings.tryAcquireBroadcastBackupSlot()) return
        val password: String? = settings.getBackupPasswordEnc()

        if (password == null || password.isEmpty()) {
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, R.string.backup_receiver_no_password_set)
            return
        }

        if (settings.getEncryption() != Constants.EncryptionType.KEYSTORE) {
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, R.string.backup_receiver_custom_encryption_failed)
            return
        }

        // WorkManager runs the backup in the background, so it is not limited by the broadcast's
        // short time budget and keeps going after this method returns. KEEP means a backup that is
        // already queued or running is not duplicated.
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(EncryptedBackupWorker::class.java).build())
    }
}
