package org.shadowice.flocke.andotp

import android.app.AlertDialog
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.MainActivity
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.GoogleAuthenticatorMigration
import org.shadowice.flocke.andotp.Utilities.MigrationBatchCollector
import org.shadowice.flocke.andotp.Utilities.MigrationBatchStatus
import org.shadowice.flocke.andotp.Utilities.MigrationParseResult
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultCommitDecision
import org.shadowice.flocke.andotp.Utilities.VaultReadResult
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult
import org.shadowice.flocke.andotp.View.EntriesCardAdapter
import org.shadowice.flocke.andotp.View.TagsAdapter

/** Uses a disposable real vault to exercise batch persistence and duplicate handling. */
@RunWith(AndroidJUnit4::class)
class GoogleMigrationDeviceTest {
    @Test
    fun multipartAccountsAreSavedTogetherAndDuplicatesAreSkipped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceTestSupport.finishActivities()
        VaultSession.lock()
        assumeTrue("refusing to overwrite an existing vault", DatabaseHelper.canInitialize(context))
        DeviceTestSupport.freshPreferences(context)
        val settings = Settings(context)
        val prepared = settings.prepareAuthCredentials("a strong test passphrase")
        val key = EncryptionHelper.generateSymmetricKey(prepared.key)
        var adapter: EntriesCardAdapter? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            assertTrue(DatabaseHelper.changeVaultEncryption(context, null, key, true) { id ->
                VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, Constants.EncryptionType.PASSWORD,
                    Constants.AuthMethod.PASSWORD, prepared, true))
            } is VaultWriteResult.Saved)
            val attempt = VaultSession.beginUnlock().generation
            assertTrue(VaultSession.activate(key, attempt) is SessionCommandResult.Applied)
            val epoch = VaultSession.generation()
            val collector = MigrationBatchCollector(epoch)
            fun part(uri: String) = (GoogleAuthenticatorMigration.parse(uri) as MigrationParseResult.Success).payload
            assertEquals(MigrationBatchStatus.ADDED, collector.add(part(PART_TWO), epoch, true))
            assertEquals(MigrationBatchStatus.COMPLETE, collector.add(part(PART_ONE), epoch, true))
            val values = requireNotNull(collector.takeAccounts(epoch, true))
            val imported = try { ArrayList(values.map { Entry(it) }) } finally { values.forEach { it.close() } }
            // An exact duplicate within the batch must not produce a third account.
            imported.add(successAccounts(PART_ONE).single())
            instrumentation.runOnMainSync {
                val created = EntriesCardAdapter(context, TagsAdapter(context, hashMapOf()))
                adapter = created
                created.encryptionKey = key
                assertTrue(created.loadEntries())
                val result = requireNotNull(created.importEntries(imported))
                assertEquals(2, result.added)
                assertEquals(1, result.skipped)
                assertEquals(listOf("alice", "bob"), created.getEntries().map { it.getLabel() })
            }
            val stored = DatabaseHelper.readVault(context, key) as VaultReadResult.Loaded
            stored.use {
                val entries = stored.takeEntries()
                try { assertEquals(listOf("alice", "bob"), entries.map { it.getLabel() }) }
                finally { entries.forEach { it.destroy() } }
            }

            // A bad second item is rejected before any item is committed.
            val valid = Entry("otpauth://totp/new?secret=JBSWY3DPEHPK3PXP")
            val invalid = Entry("otpauth://totp/bad?secret=JBSWY3DPEHPK3PXP").also { it.destroy() }
            instrumentation.runOnMainSync {
                try { requireNotNull(adapter).importEntries(arrayListOf(valid, invalid)) }
                catch (_: IllegalArgumentException) { }
                assertNull(valid.getSecret())
                assertEquals(2, requireNotNull(adapter).getEntries().size)
            }
            val after = DatabaseHelper.readVault(context, key) as VaultReadResult.Loaded
            after.use {
                val entries = after.takeEntries()
                try { assertEquals(2, entries.size) } finally { entries.forEach { it.destroy() } }
            }
        } finally {
            instrumentation.runOnMainSync { adapter?.lockEntries() }
            prepared.destroy()
            VaultSession.lock()
            DatabaseHelper.wipeDatabase(context)
            DeviceTestSupport.wipePreferences(context)
        }
    }

    @Test
    fun activityRequiresConfirmationAndRelockDismissesStaleBatches() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceTestSupport.finishActivities()
        VaultSession.lock()
        assumeTrue("refusing to overwrite an existing vault", DatabaseHelper.canInitialize(context))
        DeviceTestSupport.freshPreferences(context)
        val settings = Settings(context)
        settings.setBoolean(R.string.settings_key_dialog_hide_auto_time, true)
        val prepared = settings.prepareAuthCredentials("a strong test passphrase")
        val key = EncryptionHelper.generateSymmetricKey(prepared.key)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            assertTrue(DatabaseHelper.changeVaultEncryption(context, null, key, true) { id ->
                VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, Constants.EncryptionType.PASSWORD,
                    Constants.AuthMethod.PASSWORD, prepared, true))
            } is VaultWriteResult.Saved)
            val epoch = VaultSession.beginUnlock().generation
            assertTrue(VaultSession.activate(key, epoch) is SessionCommandResult.Applied)
            DeviceTestSupport.startActivity(context, MainActivity::class.java)
            val main = requireNotNull(DeviceTestSupport.awaitResumed<MainActivity>())
            val add = MainActivity::class.java.getDeclaredMethod("addQRCode", String::class.java).apply { isAccessible = true }
            val dialogField = MainActivity::class.java.getDeclaredField("activeMigrationDialog").apply { isAccessible = true }
            val collectorField = MainActivity::class.java.getDeclaredField("migrationCollector").apply { isAccessible = true }
            instrumentation.runOnMainSync {
                add.invoke(main, PART_ONE)
                val progress = dialogField.get(main) as AlertDialog
                assertTrue(progress.isShowing)
                add.invoke(main, PART_TWO)
                val confirmation = dialogField.get(main) as AlertDialog
                assertTrue(confirmation.isShowing)
                requireNotNull(confirmation.getButton(AlertDialog.BUTTON_POSITIVE)).performClick()
            }
            instrumentation.waitForIdleSync()
            var read = DatabaseHelper.readVault(context, key) as VaultReadResult.Loaded
            read.use {
                val entries = read.takeEntries()
                try { assertEquals(listOf("alice", "bob"), entries.map { it.getLabel() }) }
                finally { entries.forEach { it.destroy() } }
            }
            instrumentation.runOnMainSync {
                add.invoke(main, PART_ONE)
                add.invoke(main, PART_TWO)
                val stale = dialogField.get(main) as AlertDialog
                val button = requireNotNull(stale.getButton(AlertDialog.BUTTON_POSITIVE))
                VaultSession.lock()
                assertFalse(stale.isShowing)
                assertNull(collectorField.get(main))
                button.performClick() // A delayed click from the old dialog must do nothing.
            }
            read = DatabaseHelper.readVault(context, key) as VaultReadResult.Loaded
            read.use {
                val entries = read.takeEntries()
                try { assertEquals(2, entries.size) } finally { entries.forEach { it.destroy() } }
            }
        } finally {
            DeviceTestSupport.finishActivities()
            prepared.destroy()
            VaultSession.lock()
            DatabaseHelper.wipeDatabase(context)
            DeviceTestSupport.wipePreferences(context)
        }
    }

    private fun successAccounts(uri: String): List<Entry> {
        val payload = (GoogleAuthenticatorMigration.parse(uri) as MigrationParseResult.Success).payload
        payload.use {
            val values = payload.takeAccounts()
            return try { values.map { Entry(it) } } finally { values.forEach { it.close() } }
        }
    }

    private companion object {
        const val PART_ONE = "otpauth-migration://offline?data=CiIKCkhlbGxvId6tvu8SBWFsaWNlGgdFeGFtcGxlIAEoATACEAIYAiAAKLlg"
        const val PART_TWO = "otpauth-migration://offline?data=CioKFDEyMzQ1Njc4OTAxMjM0NTY3ODkwEgNib2IaB0V4YW1wbGUgASgBMAIQAhgCIAEouWA%3D"
    }
}
