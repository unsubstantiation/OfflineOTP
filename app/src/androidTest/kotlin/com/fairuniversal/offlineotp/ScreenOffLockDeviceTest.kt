/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Device regression for andOTP #736 ("backup fails on screen-off/home") and the screen-off half
 * of #712: every vault activity is registered for ACTION_SCREEN_OFF, and with the default
 * "relock on screen off" setting the receiver must revoke the session and hide the decrypted
 * entries. MainActivity deliberately stays alive (instead of finishing) so the user comes back to
 * the unlock prompt rather than to an empty launcher.
 *
 * ACTION_SCREEN_OFF is a protected broadcast, so a test cannot inject it through the system.
 * The receiver instance the activity registers is idempotent and holds no state, which lets the
 * test deliver the same intent the platform would deliver.
 */
package com.fairuniversal.offlineotp

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.fairuniversal.offlineotp.Activities.MainActivity
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.DatabaseHelper
import com.fairuniversal.offlineotp.Utilities.EncryptionHelper
import com.fairuniversal.offlineotp.Utilities.SessionCommandResult
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.Utilities.VaultCommitDecision
import com.fairuniversal.offlineotp.Utilities.VaultSession
import com.fairuniversal.offlineotp.Utilities.VaultWriteResult

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
        try {
            // The vault and the session must exist before MainActivity starts: launching it
            // without a vault makes it report an unavailable vault and revoke the generation.
            val initialized = DatabaseHelper.changeVaultEncryption(context, null, key, true) { transactionId ->
                VaultCommitDecision.fromCommit(settings.commitVaultSettings(
                    transactionId, Constants.EncryptionType.PASSWORD, Constants.AuthMethod.NONE,
                    null, true))
            }
            assertTrue("could not create an empty test vault", initialized is VaultWriteResult.Saved)
            assertTrue("could not activate the test vault",
                VaultSession.activate(key, generation) is SessionCommandResult.Applied)

            DeviceTestSupport.startActivity(context, MainActivity::class.java)
            val resumed = DeviceTestSupport.awaitResumed<MainActivity>()
            assertNotNull("MainActivity never resumed; on screen: " +
                DeviceTestSupport.resumedActivityNames(), resumed)
            val activity = requireNotNull(resumed)

            var finishing = false
            var entriesVisible = View.VISIBLE
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue("relock-on-screen-off must be on for this regression",
                    activity.settings!!.getRelockOnScreenOff())
                // The platform delivers exactly this intent to the registered receiver.
                activity.ScreenOffReceiver().onReceive(
                    activity, Intent(Intent.ACTION_SCREEN_OFF).setAction(Intent.ACTION_SCREEN_OFF))
                finishing = activity.isFinishing()
                entriesVisible = requireNotNull(activity.findViewById<View>(R.id.cardList)).visibility
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertNull("screen-off did not revoke the vault key", VaultSession.getKey())
            assertEquals("screen-off left the decrypted entries on display",
                View.INVISIBLE, entriesVisible)
            assertFalse("MainActivity should stay alive to ask for the PIN on return", finishing)
        } finally {
            DeviceTestSupport.finishActivities()
            DatabaseHelper.wipeDatabase(context)
            DeviceTestSupport.wipePreferences(context)
        }
    }
}
