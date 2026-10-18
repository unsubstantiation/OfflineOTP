/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Smoke launch of the first-run screen: the activity must come up and reach its resumed state
 * on a fresh application instance. Routing/authentication behaviour is covered by the app's
 * own gates; this only pins that the launch path does not crash on a clean install.
 */
package org.shadowice.flocke.andotp

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.IntroScreenActivity

@RunWith(AndroidJUnit4::class)
class IntroScreenLaunchTest {
    @Test
    fun introScreenLaunchesOnAFreshInstance() {
        ActivityScenario.launch(IntroScreenActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        }
    }
}
