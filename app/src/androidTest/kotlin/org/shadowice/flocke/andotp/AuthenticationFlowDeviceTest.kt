/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Regression coverage for the two authentication reports in issue #39:
 *
 *  - #208 "Back takes you back to app bypassing password": the unlock prompt fails closed, so a
 *    cancelled prompt revokes the session and MainActivity removes its task instead of showing
 *    the entry list. The positive case (the right PIN still unlocks) is asserted too, so the
 *    fail-closed path cannot be satisfied by simply never unlocking.
 *  - #712 "Re-lock on inactivity not working": the idle deadline uses elapsed realtime and is
 *    kept across a pause, so time spent with the app in the background still counts. The test
 *    backgrounds the activity, waits past a one-second timeout and resumes it.
 *
 * The suite owns the vault it creates and wipes it (and the preferences) afterwards.
 */
package org.shadowice.flocke.andotp

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.preference.PreferenceManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.AuthenticateActivity
import org.shadowice.flocke.andotp.Activities.MainActivity
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultCommitDecision
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class AuthenticationFlowDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aCancelledUnlockPromptCannotRevealTheVault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installPinVault(context, PIN)
        try {
            assertNull("the test vault must start locked", VaultSession.getKey())

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val prompt = awaitResumed<AuthenticateActivity>()
                assertNotNull("the locked vault did not request authentication", prompt)

                instrumentation.runOnMainSync { prompt!!.onBackPressed() }
                instrumentation.waitForIdleSync()

                assertNull("cancelling authentication left a vault key", VaultSession.getKey())
                assertTrue("MainActivity stayed reachable after a cancelled unlock",
                    awaitScenarioState(scenario, ActivityScenario.State.DESTROYED))
            }
        } finally {
            cleanUp(context)
        }
    }

    @Test
    fun theConfiguredPinStillUnlocksTheVault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installPinVault(context, PIN)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val prompt = awaitResumed<AuthenticateActivity>()
                assertNotNull("the locked vault did not request authentication", prompt)

                instrumentation.runOnMainSync {
                    val input = requireNotNull(prompt!!.findViewById<EditText>(R.id.passwordEdit))
                    input.setText(PIN)
                    requireNotNull(prompt.findViewById<Button>(R.id.buttonUnlock)).performClick()
                }

                assertTrue("the correct PIN did not restore the vault session", awaitVaultKey())
                assertNotNull("the main screen did not come back", awaitResumed<MainActivity>())
                scenario.onActivity { activity ->
                    val cards = requireNotNull(activity.findViewById<View>(R.id.cardList))
                    assertEquals("the entry list is not visible after unlocking", View.VISIBLE,
                        cards.visibility)
                }
            }
        } finally {
            cleanUp(context)
        }
    }

    @Test
    fun backgroundTimeCountsTowardsTheInactivityRelock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = installPinVault(context, PIN)
        enableInactivityRelock(context, seconds = 1)
        try {
            val generation = VaultSession.beginUnlock().generation
            assertTrue("the test session could not be activated",
                VaultSession.activate(key, generation) is SessionCommandResult.Applied)

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                assertNotNull("an active session was dropped on launch", VaultSession.getKey())
                assertNull("the idle timeout fired before the deadline",
                    awaitResumed<AuthenticateActivity>(timeoutMillis = 500))

                scenario.moveToState(Lifecycle.State.CREATED)
                SystemClock.sleep(1_600)
                scenario.moveToState(Lifecycle.State.RESUMED)

                assertNotNull("background time did not trigger the inactivity prompt",
                    awaitResumed<AuthenticateActivity>())
                assertNull("the idle-expired session still held a vault key", VaultSession.getKey())
            }
        } finally {
            cleanUp(context)
        }
    }

    /** Creates a password-encrypted vault whose authentication method is the given PIN. */
    private fun installPinVault(context: Context, pin: String): SecretKey {
        assumeTrue("a vault already exists; refusing to overwrite it", DatabaseHelper.canInitialize(context))
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().commit()
        PreferenceManager.setDefaultValues(context, R.xml.preferences, true)

        val settings = Settings(context)
        val prepared = settings.prepareAuthCredentials(pin)
        val key = EncryptionHelper.generateSymmetricKey(prepared.key)
        try {
            val write = DatabaseHelper.changeVaultEncryption(context, null, key, true) { transactionId ->
                VaultCommitDecision.fromCommit(settings.commitVaultSettings(
                    transactionId, Constants.EncryptionType.PASSWORD, Constants.AuthMethod.PIN,
                    prepared, true))
            }
            assertTrue("test vault initialization failed: $write", write is VaultWriteResult.Saved)
        } finally {
            prepared.destroy()
        }
        VaultSession.lock()
        return key
    }

    private fun enableInactivityRelock(context: Context, seconds: Int) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(context.getString(R.string.settings_key_auth_inactivity), true)
            .putInt(context.getString(R.string.settings_key_auth_inactivity_delay), seconds)
            .commit()
    }

    private fun cleanUp(context: Context) {
        VaultSession.lock()
        DatabaseHelper.wipeDatabase(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    /** Waits for a resumed instance of [T]; null when none appears before the timeout. */
    private inline fun <reified T : Activity> awaitResumed(timeoutMillis: Long = 10_000): T? {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var found: Activity? = null
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is T }
            }
            val match = found as? T
            if (match != null) return match
            SystemClock.sleep(50)
        }
        return null
    }

    private fun awaitVaultKey(timeoutMillis: Long = 30_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (VaultSession.getKey() != null) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private fun awaitScenarioState(
        scenario: ActivityScenario<MainActivity>,
        expected: ActivityScenario.State,
        timeoutMillis: Long = 10_000
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (scenario.state == expected) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private companion object {
        const val PIN = "1234"
    }
}
