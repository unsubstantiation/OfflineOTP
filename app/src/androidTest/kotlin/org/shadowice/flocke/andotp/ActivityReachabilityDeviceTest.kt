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
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.AboutActivity
import org.shadowice.flocke.andotp.Activities.BackupActivity
import org.shadowice.flocke.andotp.Activities.SettingsActivity
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
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
                    preference!!.performClick()
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
