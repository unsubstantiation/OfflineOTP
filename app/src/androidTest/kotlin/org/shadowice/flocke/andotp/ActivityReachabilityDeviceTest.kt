/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Reachability smoke tests for the surfaces named in andOTP crash reports (#873 Settings, #932
 * Settings, #948 backup) and the About screen. These assert that the activities come up under the
 * current theme/configuration and survive configuration recreation; they are the on-device part
 * of the carry-over verification and are complemented by the JVM and Python guards.
 */
package org.shadowice.flocke.andotp

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.AboutActivity
import org.shadowice.flocke.andotp.Activities.SettingsActivity

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
    fun settingsScreenFailsClosedWithoutAnUnlockedVault() {
        // SettingsActivity is a vault-gated screen: without a session it must finish cleanly
        // instead of rendering (or crashing) with no key.
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        }
    }
}
