/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * The first-run wizard must follow the device orientation (andOTP #591) and must survive a
 * configuration change at any slide: the setup state is committed before the final slide, so a
 * recreation must neither re-run the setup task nor strand the user on the last slide.
 */
package org.shadowice.flocke.andotp

import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun introScreenDoesNotForceAnOrientation() {
        ActivityScenario.launch(IntroScreenActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("the first-run wizard must follow the auto-rotate setting",
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
            }
        }
    }

    @Test
    fun introScreenSurvivesRecreation() {
        ActivityScenario.launch(IntroScreenActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse("the wizard must not finish on a configuration change", activity.isFinishing())
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
            }
        }
    }
}
