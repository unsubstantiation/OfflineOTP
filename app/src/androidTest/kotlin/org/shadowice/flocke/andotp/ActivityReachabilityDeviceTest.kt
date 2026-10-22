/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Reachability smoke tests for the andOTP crash reports (#873 Settings, #932 Settings tap, and
 * #948 backup) and the About screen. They assert that the activities come up under the current
 * theme/configuration and survive recreation; picker/provider behavior still needs device-specific checks.
 */
package org.shadowice.flocke.andotp

import android.content.Context
import android.preference.PreferenceManager
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.AboutActivity
import org.shadowice.flocke.andotp.Activities.BackupActivity
import org.shadowice.flocke.andotp.Activities.SettingsActivity
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultSession
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class ActivityReachabilityDeviceTest {
    @Test
    fun aboutScreenLaunchesAndSurvivesRecreation() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                assertNotNull(activity)
                assertFalse(activity.isFinishing())
            }
        }
    }

    @Test
    fun backupScreenLaunchesAndSurvivesRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().commit()
        PreferenceManager.setDefaultValues(context, R.xml.preferences, true)

        val generation = VaultSession.beginUnlock().generation
        val key = SecretKeySpec(ByteArray(32) { 0x5a }, "AES")
        assertTrue(VaultSession.activate(key, generation) is SessionCommandResult.Applied)
        try {
            ActivityScenario.launch(BackupActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<android.view.View>(R.id.container_toolbar))
                    assertFalse(activity.isFinishing())
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<android.view.View>(R.id.container_toolbar))
                    assertFalse(activity.isFinishing())
                }
            }
        } finally {
            VaultSession.lock()
            preferences.edit().clear().commit()
        }
    }

    /**
     * #923 "Single-tap doesn't work anymore": both tap actions are preference-driven and default
     * to nothing (upstream's documented default). Every value the settings list offers must map
     * back to a [Constants.TapMode], otherwise the card tap handler can never run that action.
     */
    @Test
    fun tapPreferencesDefaultToNothingAndMapEveryListedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().commit()
        PreferenceManager.setDefaultValues(context, R.xml.preferences, true)
        try {
            val settings = Settings(context)
            assertEquals(Constants.TapMode.NOTHING, settings.getTapSingle())
            assertEquals(Constants.TapMode.NOTHING, settings.getTapDouble())

            val key = context.getString(R.string.settings_key_tap_single)
            for (mode in Constants.TapMode.values()) {
                preferences.edit().putString(key, mode.name.lowercase(java.util.Locale.ROOT)).commit()
                assertEquals("the settings value for $mode is not mapped back", mode,
                    settings.getTapSingle())
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun settingsScreenLaunchesWithFreshDefaultsAndUnlockedVault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().commit()
        PreferenceManager.setDefaultValues(context, R.xml.preferences, true)

        val generation = VaultSession.beginUnlock().generation
        val key = SecretKeySpec(ByteArray(32) { 0x5a }, "AES")
        assertTrue(VaultSession.activate(key, generation) is SessionCommandResult.Applied)
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<android.view.View>(R.id.container_toolbar))
                    assertFalse(activity.isFinishing())
                    val fragment = activity.getFragmentManager()
                        .findFragmentById(R.id.container_content) as SettingsActivity.SettingsFragment
                    val keyName = activity.getString(R.string.settings_key_show_prev_token)
                    val preference = fragment.findPreference(keyName)
                    assertNotNull("show previous token preference is missing", preference)
                    // The framework preference has no public click entry point and
                    // PreferenceFragment does not expose its list, so reach the standard
                    // preference list view (#932 is a crash on a touch of one of its rows).
                    val list = activity.findViewById<ListView>(android.R.id.list)
                    assertNotNull("the settings list is missing", list)
                    val adapter = requireNotNull(list!!.adapter)
                    var position = -1
                    for (index in 0 until adapter.getCount()) {
                        if (adapter.getItem(index) === preference) {
                            position = index
                            break
                        }
                    }
                    assertTrue("the preference has no row in the settings list", position >= 0)
                    val before = preference!!.getSharedPreferences()
                        .getBoolean(keyName, false)
                    assertTrue("the settings row did not handle the touch",
                        list.performItemClick(null, position, adapter.getItemId(position)))
                    val after = preference.getSharedPreferences()
                        .getBoolean(keyName, false)
                    assertFalse("touching the Settings option did not persist its value",
                        before == after)
                    assertFalse("touching the Settings option finished the screen", activity.isFinishing())
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<android.view.View>(R.id.container_toolbar))
                    assertFalse(activity.isFinishing())
                }
            }
        } finally {
            VaultSession.lock()
            preferences.edit().clear().commit()
        }
    }
}
