/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Regression coverage for the two authentication reports in issue #39:
 *
 *  - #208 "Back takes you back to app bypassing password": the unlock prompt fails closed, so a
 *    cancelled prompt revokes the session and MainActivity removes its task instead of showing
 *    the entry list. The positive case (the right password still unlocks) is asserted too, so the
 *    fail-closed path cannot be satisfied by simply never unlocking.
 *  - #712 "Re-lock on inactivity not working": the idle deadline uses elapsed realtime and is
 *    kept across a pause, so time spent with the app in the background still counts. The test
 *    unlocks, covers the screen with another activity, waits past the deadline, and comes back.
 *
 * MainActivity starts the authentication screen from onResume and therefore never settles in
 * RESUMED while the vault is locked, so these tests start it through
 * [DeviceTestSupport.startActivity] instead of ActivityScenario.launch.
 *
 * The suite owns the vault it creates and wipes it (and the preferences) afterwards.
 */
package com.fairuniversal.offlineotp

import android.content.Context
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.fairuniversal.offlineotp.Activities.AboutActivity
import com.fairuniversal.offlineotp.Activities.AuthenticateActivity
import com.fairuniversal.offlineotp.Activities.MainActivity
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.DatabaseHelper
import com.fairuniversal.offlineotp.Utilities.EncryptionHelper
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupHeader
import com.fairuniversal.offlineotp.Utilities.SessionCommandResult
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.Utilities.VaultCommitDecision
import com.fairuniversal.offlineotp.Utilities.VaultSession
import com.fairuniversal.offlineotp.Utilities.VaultWriteResult
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class AuthenticationFlowDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aCancelledUnlockPromptCannotRevealTheVault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installPasswordVault(context, PASSWORD)
        try {
            assertNull("the test vault must start locked", VaultSession.getKey())

            val main = DeviceTestSupport.startActivity(context, MainActivity::class.java)
            val prompt = DeviceTestSupport.awaitResumed<AuthenticateActivity>()
            assertNotNull("the locked vault did not request authentication", prompt)

            instrumentation.runOnMainSync { prompt!!.onBackPressed() }
            instrumentation.waitForIdleSync()

            assertNull("cancelling authentication left a vault key", VaultSession.getKey())
            assertTrue("MainActivity stayed reachable after a cancelled unlock",
                DeviceTestSupport.awaitFinishing(main))
            assertNull("the entry list came back after a cancelled unlock",
                DeviceTestSupport.awaitResumed<MainActivity>(timeoutMillis = 2_000))
        } finally {
            DeviceTestSupport.finishActivities()
            cleanUp(context)
        }
    }

    @Test
    fun theConfiguredPasswordStillUnlocksTheVault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installPasswordVault(context, PASSWORD)
        try {
            DeviceTestSupport.startActivity(context, MainActivity::class.java)
            val prompt = DeviceTestSupport.awaitResumed<AuthenticateActivity>()
            assertNotNull("the locked vault did not request authentication", prompt)

            instrumentation.runOnMainSync {
                val input = requireNotNull(prompt!!.findViewById<EditText>(R.id.passwordEdit))
                input.setText(PASSWORD)
                requireNotNull(prompt.findViewById<Button>(R.id.buttonUnlock)).performClick()
            }

            assertTrue("the correct password did not restore the vault session", awaitVaultKey())
            val main = DeviceTestSupport.awaitResumed<MainActivity>()
            assertNotNull("the main screen did not come back", main)
            instrumentation.runOnMainSync {
                val cards = requireNotNull(main!!.findViewById<android.view.View>(R.id.cardList))
                assertEquals("the entry list is not visible after unlocking", android.view.View.VISIBLE,
                    cards.visibility)
            }
        } finally {
            DeviceTestSupport.finishActivities()
            cleanUp(context)
        }
    }

    @Test
    fun aVaultStoredAsAPinStillOpensThroughThePasswordPrompt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installPasswordVault(context, LEGACY_PIN)
        try {
            // What an older release left behind: the credential is a short PIN and the stored
            // method names a login this build no longer offers.
            android.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(context.getString(R.string.settings_key_auth), "pin")
                .commit()
            assertEquals("a removed method must read as a password, not crash",
                Constants.AuthMethod.PASSWORD, Settings(context).getAuthMethod())

            DeviceTestSupport.startActivity(context, MainActivity::class.java)
            val prompt = DeviceTestSupport.awaitResumed<AuthenticateActivity>()
            assertNotNull("the legacy vault did not ask for its credential", prompt)

            instrumentation.runOnMainSync {
                val input = requireNotNull(prompt!!.findViewById<EditText>(R.id.passwordEdit))
                input.setText(LEGACY_PIN)
                requireNotNull(prompt.findViewById<Button>(R.id.buttonUnlock)).performClick()
            }

            assertTrue("the PIN credential no longer opened its vault", awaitVaultKey())
            val main = DeviceTestSupport.awaitResumed<MainActivity>()
            assertNotNull("the main screen did not come back", main)
        } finally {
            DeviceTestSupport.finishActivities()
            cleanUp(context)
        }
    }

    @Test
    fun aPasswordUnlockProvidesASeparatePortableBackupKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installPasswordVault(context, PASSWORD)
        try {
            DeviceTestSupport.startActivity(context, MainActivity::class.java)
            val prompt = DeviceTestSupport.awaitResumed<AuthenticateActivity>()
            assertNotNull("the password prompt was not shown", prompt)
            instrumentation.runOnMainSync {
                requireNotNull(prompt!!.findViewById<EditText>(R.id.passwordEdit)).setText(PASSWORD)
                requireNotNull(prompt.findViewById<Button>(R.id.buttonUnlock)).performClick()
            }
            assertTrue("the correct password did not unlock", awaitVaultKey())
            val lease = VaultSession.copyBackupKey()
            assertNotNull("the password unlock did not publish a backup key", lease)
            lease!!.use {
                val plaintext = "[]".toByteArray(Charsets.UTF_8)
                val frame = EncryptedBackupHeader.parse(it.key.encrypt(plaintext))
                val recoveryKey = frame.deriveKey(PASSWORD)
                assertTrue(EncryptionHelper.decrypt(recoveryKey, frame.getEncrypted()).contentEquals(plaintext))
            }
            VaultSession.lock()
            assertNull("locking retained the backup key", VaultSession.copyBackupKey())
        } finally {
            DeviceTestSupport.finishActivities()
            cleanUp(context)
        }
    }

    @Test
    fun backgroundTimeCountsTowardsTheInactivityRelock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = installPasswordVault(context, PASSWORD)
        enableInactivityRelock(context, seconds = INACTIVITY_SECONDS)
        try {
            val generation = VaultSession.beginUnlock().generation
            assertTrue("the test session could not be activated",
                VaultSession.activate(key, generation) is SessionCommandResult.Applied)

            DeviceTestSupport.startActivity(context, MainActivity::class.java)
            assertNotNull("MainActivity did not resume on an active session; on screen: " +
                DeviceTestSupport.resumedActivityNames(),
                DeviceTestSupport.awaitResumed<MainActivity>())
            assertNotNull("an active session was dropped on launch", VaultSession.getKey())
            assertNull("the inactivity timeout fired while the screen was in front",
                DeviceTestSupport.awaitResumed<AuthenticateActivity>(timeoutMillis = 500))

            // Another activity in front is the device equivalent of the app being backgrounded;
            // the session must not survive on the strength of the idle timer being paused.
            val cover = DeviceTestSupport.startActivity(context, AboutActivity::class.java)
            assertNotNull("the covering activity did not resume", DeviceTestSupport.awaitResumed<AboutActivity>())
            assertNull("covering the screen did not pause MainActivity",
                DeviceTestSupport.awaitResumed<MainActivity>(timeoutMillis = 500))
            SystemClock.sleep(INACTIVITY_SECONDS * 1_000L + 700L)
            // finish(), not finishAndRemoveTask(): the latter finishes every activity in the task,
            // which would destroy MainActivity instead of resuming it.
            instrumentation.runOnMainSync { cover.finish() }

            // MainActivity.onResume hits the expired deadline before it can settle in RESUMED:
            // it revokes the session and immediately starts the prompt, so the observable
            // outcome is the prompt, not a resumed entry list.
            assertNotNull("background time did not trigger the inactivity prompt; on screen: " +
                DeviceTestSupport.resumedActivityNames(),
                DeviceTestSupport.awaitResumed<AuthenticateActivity>())
            assertNull("the idle-expired session still held a vault key", VaultSession.getKey())
            assertNull("the entry list came back after the inactivity timeout",
                DeviceTestSupport.awaitResumed<MainActivity>(timeoutMillis = 1_000))
        } finally {
            DeviceTestSupport.finishActivities()
            cleanUp(context)
        }
    }

    /** Creates a password-encrypted vault and returns the key it was written with. */
    private fun installPasswordVault(context: Context, password: String): SecretKey {
        assumeTrue("a vault already exists; refusing to overwrite it", DatabaseHelper.canInitialize(context))
        DeviceTestSupport.freshPreferences(context)
        val settings = Settings(context)
        val prepared = settings.prepareAuthCredentials(password)
        val key = EncryptionHelper.generateSymmetricKey(prepared.key)
        try {
            assertTrue("password vault initialization failed", DatabaseHelper.changeVaultEncryption(context, null, key, true) { id ->
                VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, Constants.EncryptionType.PASSWORD,
                    Constants.AuthMethod.PASSWORD, prepared, true))
            } is VaultWriteResult.Saved)
        } finally {
            prepared.destroy()
        }
        VaultSession.lock()
        return key
    }

    private fun enableInactivityRelock(context: Context, seconds: Int) {
        android.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(context.getString(R.string.settings_key_auth_inactivity), true)
            .putInt(context.getString(R.string.settings_key_auth_inactivity_delay), seconds)
            .commit()
    }

    private fun cleanUp(context: Context) {
        // Let the activities finish (and close their timers) before wiping the vault, otherwise a
        // late inactivity callback could revoke the session of the next test.
        DeviceTestSupport.finishActivities()
        DatabaseHelper.wipeDatabase(context)
        DeviceTestSupport.wipePreferences(context)
    }

    private fun awaitVaultKey(timeoutMillis: Long = 30_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (VaultSession.getKey() != null) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private companion object {
        /** The credential an older release's PIN login would have left in the vault. */
        const val LEGACY_PIN = "1234"
        const val PASSWORD = "correct horse battery staple"
        const val INACTIVITY_SECONDS = 4
    }
}
