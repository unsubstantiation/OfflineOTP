package org.shadowice.flocke.andotp

import android.content.Context
import android.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Tasks.ChangeCredentialsTask
import org.shadowice.flocke.andotp.Tasks.FinishIntroTask
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupHeader
import org.shadowice.flocke.andotp.Utilities.EntryPersistence
import org.shadowice.flocke.andotp.Utilities.LoginBackupKey
import org.shadowice.flocke.andotp.Utilities.PendingAutomaticBackup
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultCommitDecision
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult

/** Uses a disposable password vault and real private-file writes; no SAF provider is simulated. */
@RunWith(AndroidJUnit4::class)
class AutomaticBackupDeviceTest {
    @Test
    fun onlyCommittedChangesLeaveARetryMarker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceTestSupport.finishActivities()
        VaultSession.lock()
        assumeTrue("refusing to overwrite an existing vault", DatabaseHelper.canInitialize(context))
        DeviceTestSupport.freshPreferences(context)
        val settings = Settings(context)
        val prepared = settings.prepareAuthCredentials(PASSWORD)
        val key = EncryptionHelper.generateSymmetricKey(prepared.key)
        val entry = Entry("otpauth://hotp/alice?secret=JBSWY3DPEHPK3PXP&issuer=Example&counter=1")
        try {
            assertTrue(DatabaseHelper.changeVaultEncryption(context, null, key, true) { id ->
                VaultCommitDecision.fromCommit(settings.commitVaultSettings(id,
                    Constants.EncryptionType.PASSWORD, Constants.AuthMethod.PASSWORD, prepared, true))
            } is VaultWriteResult.Saved)
            val generation = VaultSession.beginUnlock().generation
            LoginBackupKey.derive(PASSWORD).use { candidate ->
                assertTrue(VaultSession.offerBackupKey(candidate, generation))
            }
            assertTrue(VaultSession.activate(key, generation) is SessionCommandResult.Applied)
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(context.getString(R.string.settings_key_auto_backup_password_enc), "all_edits")
                .putBoolean(context.getString(R.string.settings_key_auto_backup_use_login), true)
                .commit()
            val persistence = EntryPersistence(context)
            val empty = arrayListOf<Entry>()
            assertTrue(persistence.save(empty, key, true, null) is VaultWriteResult.Saved)
            assertNull("an unchanged vault must not create a retry marker", PendingAutomaticBackup.read(context))

            // Without a selected folder the write succeeds, but the snapshot remains pending.
            val entries = arrayListOf(entry)
            assertTrue(persistence.save(entries, key, true, null) is VaultWriteResult.Saved)
            val marker = PendingAutomaticBackup.read(context)
            assertNotNull("a committed unbacked change needs a retry marker", marker)
            assertTrue(persistence.save(entries, key, true, null) is VaultWriteResult.Saved)
            assertEquals("a no-op save must not create a new pending change", marker,
                PendingAutomaticBackup.read(context))

            entry.setCounter(2)
            assertTrue(persistence.save(entries, key, true, null) is VaultWriteResult.Saved)
            assertFalse("a HOTP counter change must replace the pending revision",
                marker == PendingAutomaticBackup.read(context))
            VaultSession.lock()
            assertTrue(persistence.save(entries, key, true, null) is VaultWriteResult.Rejected)
            assertNotNull("locking must not lose the pending retry", PendingAutomaticBackup.read(context))
            val reopened = VaultSession.beginUnlock().generation
            LoginBackupKey.derive(PASSWORD).use { assertTrue(VaultSession.offerBackupKey(it, reopened)) }
            assertTrue(VaultSession.activate(key, reopened) is SessionCommandResult.Applied)
            persistence.retryPending(key, null)
            assertNotNull("retry without a destination must remain pending", PendingAutomaticBackup.read(context))
            assertTrue(persistence.save(empty, key, true, null) is VaultWriteResult.Saved)
            persistence.retryPending(key, null)
            assertNull("a vault restored to its pre-change content should not need a retry", PendingAutomaticBackup.read(context))
        } finally {
            prepared.destroy()
            entry.destroy()
            VaultSession.lock()
            DatabaseHelper.wipeDatabase(context)
            DeviceTestSupport.wipePreferences(context)
        }
    }

    @Test
    fun olderOverlappingSnapshotCannotSuppressRetryOfRevertedVault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceTestSupport.finishActivities()
        VaultSession.lock()
        assumeTrue("refusing to overwrite an existing vault", DatabaseHelper.canInitialize(context))
        DeviceTestSupport.freshPreferences(context)
        val settings = Settings(context)
        val prepared = settings.prepareAuthCredentials(PASSWORD)
        val key = EncryptionHelper.generateSymmetricKey(prepared.key)
        try {
            assertTrue(DatabaseHelper.changeVaultEncryption(context, null, key, true) { id ->
                VaultCommitDecision.fromCommit(settings.commitVaultSettings(id,
                    Constants.EncryptionType.PASSWORD, Constants.AuthMethod.PASSWORD, prepared, true))
            } is VaultWriteResult.Saved)
            val generation = VaultSession.beginUnlock().generation
            LoginBackupKey.derive(PASSWORD).use { assertTrue(VaultSession.offerBackupKey(it, generation)) }
            assertTrue(VaultSession.activate(key, generation) is SessionCommandResult.Applied)
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(context.getString(R.string.settings_key_auto_backup_password_enc), "all_edits")
                .putBoolean(context.getString(R.string.settings_key_auto_backup_use_login), true)
                .commit()
            val before = PendingAutomaticBackup.fingerprint(DatabaseHelper.entriesToString(arrayListOf()), key)
            val older = PendingAutomaticBackup.stage(context, before).marker
            assertTrue(PendingAutomaticBackup.claim(context, older.token))
            val newer = PendingAutomaticBackup.stage(context, "a different pre-change digest").marker
            val persistence = EntryPersistence(context)
            // The vault has reverted, but the older snapshot may still complete after this retry.
            persistence.retryPending(key, null)
            assertEquals(newer.token, PendingAutomaticBackup.read(context)?.token)
            PendingAutomaticBackup.complete(context, older.token) // Simulate successful older export.
            PendingAutomaticBackup.release(older.token)
            assertNull(PendingAutomaticBackup.read(context)?.before)
            persistence.retryPending(key, null) // No destination: retry must remain pending.
            assertEquals(newer.token, PendingAutomaticBackup.read(context)?.token)
        } finally {
            prepared.destroy()
            VaultSession.lock()
            DatabaseHelper.wipeDatabase(context)
            DeviceTestSupport.wipePreferences(context)
        }
    }

    @Test
    fun setupAndPasswordChangesReplaceTheBackupKeyWithoutBreakingOldFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceTestSupport.finishActivities()
        VaultSession.lock()
        assumeTrue("refusing to overwrite an existing vault", DatabaseHelper.canInitialize(context))
        DeviceTestSupport.freshPreferences(context)
        try {
            val setup = object : FinishIntroTask(context, Constants.EncryptionType.PASSWORD,
                Constants.AuthMethod.PASSWORD, PASSWORD, false) {
                fun runNow(): FinishIntroTask.Result = doInBackground()
            }.runNow()
            assertTrue("password setup failed", setup.saveSuccessful)
            val vaultKey = EncryptionHelper.generateSymmetricKey(requireNotNull(setup.encryptionKey))
            assertTrue(VaultSession.activate(vaultKey, setup.generation) is SessionCommandResult.Applied)
            setup.encryptionKey?.fill(0)
            val oldLease = requireNotNull(VaultSession.copyBackupKey())
            val oldFrame = oldLease.key.encrypt("[]".toByteArray())
            val changed = object : ChangeCredentialsTask(context, Constants.EncryptionType.PASSWORD,
                vaultKey, Constants.AuthMethod.PASSWORD, NEW_PASSWORD) {
                fun runNow(): ChangeCredentialsTask.Result = doInBackground()
            }.runNow()
            assertTrue("password change failed", changed.success)
            assertFalse("old backup lease survived a password change", VaultSession.isBackupCurrent(oldLease))
            oldLease.close()
            assertTrue(VaultSession.activate(requireNotNull(changed.newEncryptionKey), VaultSession.generation())
                is SessionCommandResult.Applied)
            val newLease = requireNotNull(VaultSession.copyBackupKey())
            val newFrame = newLease.key.encrypt("[]".toByteArray())
            newLease.close()
            for ((frame, password) in listOf(oldFrame to PASSWORD, newFrame to NEW_PASSWORD)) {
                val header = EncryptedBackupHeader.parse(frame)
                val backupKey = EncryptionHelper.generateSymmetricKeyPBKDF2(password,
                    header.getIterations(), header.getSalt())
                assertEquals("[]", String(EncryptionHelper.decrypt(backupKey, header.getEncrypted())))
            }
        } finally {
            VaultSession.lock()
            DatabaseHelper.wipeDatabase(context)
            DeviceTestSupport.wipePreferences(context)
        }
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val NEW_PASSWORD = "a different strong passphrase"
    }
}
