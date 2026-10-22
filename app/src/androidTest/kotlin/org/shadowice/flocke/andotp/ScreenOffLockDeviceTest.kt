/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Device regression for andOTP #736 ("backup fails on screen-off/home") and the screen-off half
 * of #712: every vault activity is registered for ACTION_SCREEN_OFF, and with the default
 * "relock on screen off" setting the receiver must revoke the session and close the screen
 * instead of leaving decrypted entries (or a half-finished operation) on display.
 *
 * ACTION_SCREEN_OFF is a protected broadcast, so a test cannot inject it through the system.
 * The receiver instance the activity registers is idempotent and holds no state, which lets the
 * test deliver the same intent the platform would deliver.
 */
package org.shadowice.flocke.andotp

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.MainActivity
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultCommitDecision
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult

@RunWith(AndroidJUnit4::class)
class ScreenOffLockDeviceTest {
    @Test
    fun screenOffRevokesTheSessionAndClosesTheScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        VaultSession.lock()
        assumeTrue("refusing to overwrite an existing vault", DatabaseHelper.canInitialize(context))
        // Fresh defaults: relock-on-screen-off defaults to true, which is the reported setting.
        DeviceTestSupport.freshPreferences(context)

        val settings = Settings(context)
        val generation = VaultSession.beginUnlock().generation
        val key = EncryptionHelper.generateSymmetricKey(ByteArray(32) { 0x33 })
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val initialized = DatabaseHelper.changeVaultEncryption(context, null, key, true) { transactionId ->
                VaultCommitDecision.fromCommit(settings.commitVaultSettings(
                    transactionId, Constants.EncryptionType.PASSWORD, Constants.AuthMethod.NONE,
                    null, true))
            }
            assertTrue("could not create an empty test vault", initialized is VaultWriteResult.Saved)
            assertTrue("could not activate the test vault",
                VaultSession.activate(key, generation) is SessionCommandResult.Applied)

            var finishing = false
            scenario.onActivity { activity ->
                assertTrue("relock-on-screen-off must be on for this regression",
                    activity.settings!!.getRelockOnScreenOff())
                // The platform delivers exactly this intent to the registered receiver.
                activity.ScreenOffReceiver().onReceive(
                    activity, Intent(Intent.ACTION_SCREEN_OFF).setAction(Intent.ACTION_SCREEN_OFF))
                finishing = activity.isFinishing()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertNull("screen-off did not revoke the vault key", VaultSession.getKey())
            assertTrue("screen-off left the vault screen on display", finishing)
        } finally {
            DeviceTestSupport.closeQuietly(scenario)
            DeviceTestSupport.finishActivities()
            DatabaseHelper.wipeDatabase(context)
            DeviceTestSupport.wipePreferences(context)
        }
    }
}
