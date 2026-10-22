/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * State-level Android accessibility regression coverage for the expanded add menu in andOTP #498.
 * TalkBack traversal itself still requires a manual accessibility-service check.
 */
package org.shadowice.flocke.andotp

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leinardi.android.speeddial.FabWithLabelView
import com.leinardi.android.speeddial.SpeedDialView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class MainActivityAccessibilityDeviceTest {
    @Test
    fun openingAndClosingSpeedDialUpdatesAccessibilityScopeAndLabels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        VaultSession.lock()
        assumeTrue("refusing to overwrite an existing vault", DatabaseHelper.canInitialize(context))

        // Fresh defaults plus "the backup warning was shown": MainActivity otherwise starts the
        // first-run wizard, which keeps it out of RESUMED.
        DeviceTestSupport.freshPreferences(context)
        val settings = Settings(context)
        val generation = VaultSession.beginUnlock().generation
        val key = EncryptionHelper.generateSymmetricKey(ByteArray(32) { 0x21 })

        try {
            val initialized = DatabaseHelper.changeVaultEncryption(context, null, key, true) { transactionId ->
                VaultCommitDecision.fromCommit(settings.commitVaultSettings(
                    transactionId, Constants.EncryptionType.PASSWORD, Constants.AuthMethod.NONE,
                    null, true))
            }
            assertTrue("could not create an empty test vault", initialized is VaultWriteResult.Saved)
            assertTrue("could not activate the test vault",
                VaultSession.activate(key, generation) is SessionCommandResult.Applied)

            DeviceTestSupport.startActivity(context, MainActivity::class.java)
            // Not ActivityScenario.launch: MainActivity leaves RESUMED when the first-run wizard
            // or the authentication prompt is up, so the actual reason must be assertable here.
            val resumed = DeviceTestSupport.awaitResumed<MainActivity>()
            assertNotNull("MainActivity never resumed; on screen: " +
                DeviceTestSupport.resumedActivityNames(), resumed)
            val activity = requireNotNull(resumed)

            onMain {
                val dial = requireNotNull(activity.findViewById<SpeedDialView>(R.id.speedDial))
                val appBar = requireNotNull(activity.findViewById<View>(R.id.main_app_bar))
                val overlay = requireNotNull(activity.findViewById<View>(R.id.speedDialOverlay))
                assertFalse("the add menu should start closed", dial.isOpen())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, appBar.getImportantForAccessibility())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, overlay.getImportantForAccessibility())
                assertEquals(activity.getString(R.string.button_add), dial.getMainFab().contentDescription)
                assertTrue("the add button did not handle a click", dial.getMainFab().performClick())
            }
            idle()

            onMain {
                val dial = requireNotNull(activity.findViewById<SpeedDialView>(R.id.speedDial))
                val overlay = requireNotNull(activity.findViewById<View>(R.id.speedDialOverlay))
                val appBar = requireNotNull(activity.findViewById<View>(R.id.main_app_bar))
                val mainContent = requireNotNull(activity.findViewById<View>(R.id.main_content))
                assertTrue("the speed dial did not open", dial.isOpen())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    appBar.getImportantForAccessibility())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    mainContent.getImportantForAccessibility())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, overlay.getImportantForAccessibility())
                assertEquals(activity.getString(R.string.button_close_menu), overlay.contentDescription)
                assertEquals(activity.getString(R.string.button_close_menu), dial.getMainFab().contentDescription)

                val firstAction = dial.getActionItems().firstOrNull()
                assertNotNull("the expanded menu has no actions", firstAction)
                assertNotNull("the first action is not present in the view hierarchy",
                    dial.findViewById<FabWithLabelView>(firstAction!!.getId())?.getFab())
                dial.close()
            }
            idle()

            onMain {
                val dial = requireNotNull(activity.findViewById<SpeedDialView>(R.id.speedDial))
                val overlay = requireNotNull(activity.findViewById<View>(R.id.speedDialOverlay))
                val appBar = requireNotNull(activity.findViewById<View>(R.id.main_app_bar))
                assertFalse("the speed dial did not close", dial.isOpen())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
                    appBar.getImportantForAccessibility())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, overlay.getImportantForAccessibility())
                assertEquals(activity.getString(R.string.button_add), dial.getMainFab().contentDescription)
            }
        } finally {
            DeviceTestSupport.finishActivities()
            DatabaseHelper.wipeDatabase(context)
            DeviceTestSupport.wipePreferences(context)
        }
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }

    private fun idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
