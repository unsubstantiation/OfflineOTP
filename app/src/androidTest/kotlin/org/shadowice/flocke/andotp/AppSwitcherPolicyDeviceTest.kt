/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * The app switcher blackout is opt-in. Android's FLAG_SECURE hides the recents preview and blocks
 * screenshots alike, so the app releases the flag while it is paused unless the user turned on
 * "Hide in the app switcher". These tests drive the real lifecycle of a window that applies the
 * shared policy and read the flag back from the platform.
 */
package org.shadowice.flocke.andotp

import android.content.Context
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.IntroScreenActivity
import org.shadowice.flocke.andotp.Utilities.Settings

@RunWith(AndroidJUnit4::class)
class AppSwitcherPolicyDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun IntroScreenActivity.isSecure(): Boolean =
        (getWindow().attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    @Test
    fun theSettingDefaultsToShowingTheAppSwitcherPreview() {
        val preferences = DeviceTestSupport.freshPreferences(context)
        assertFalse(
            "hiding the app switcher preview must stay opt-in",
            Settings(context).getHideInAppSwitcher(),
        )
        assertFalse(
            "the stored default must be off",
            preferences.getBoolean(
                context.getString(R.string.settings_key_hide_in_app_switcher), false),
        )
    }

    @Test
    fun thePreviewIsKeptOnceTheWindowIsPaused() {
        DeviceTestSupport.freshPreferences(context)
        ActivityScenario.launch(IntroScreenActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("screenshots must stay blocked while the window is visible",
                    activity.isSecure())
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                assertFalse(
                    "the app switcher keeps its preview unless the user opts out",
                    activity.isSecure(),
                )
            }
        }
    }

    @Test
    fun enablingTheSettingBlanksThePreview() {
        val preferences = DeviceTestSupport.freshPreferences(context)
        assertTrue(
            "the setting could not be stored",
            preferences.edit().putBoolean(
                context.getString(R.string.settings_key_hide_in_app_switcher), true).commit(),
        )
        ActivityScenario.launch(IntroScreenActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                assertTrue(
                    "the paused window must stay secure so the switcher shows a blank card",
                    activity.isSecure(),
                )
            }
        }
    }
}
