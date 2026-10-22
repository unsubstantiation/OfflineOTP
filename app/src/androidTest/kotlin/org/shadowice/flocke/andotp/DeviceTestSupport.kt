/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Shared fixtures for the instrumentation suite. This file is support code, not a test class:
 * scripts/tests/test_android_test_suite.py knows that only `*Support.kt` may skip @RunWith.
 *
 * Two device-only facts drive the helpers below:
 *
 *  - MainActivity starts the first-run wizard while `pref_security_backup_warning_shown` is
 *    false, and a wizard on top keeps MainActivity from ever reaching RESUMED. Every test that
 *    launches MainActivity therefore applies the XML defaults and marks the warning as seen.
 *  - MainActivity deliberately finishes itself on failed authentication and on screen-off
 *    teardown. ActivityScenario.close() throws once an activity has finished itself, so the
 *    fail-closed tests close the scenario tolerantly.
 */
package org.shadowice.flocke.andotp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.shadowice.flocke.andotp.Utilities.VaultSession

internal object DeviceTestSupport {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * Clears every stored preference, re-applies the XML defaults, and records that the one-time
     * backup warning has been shown so MainActivity does not divert to the first-run wizard.
     */
    fun freshPreferences(context: Context): SharedPreferences {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().commit()
        PreferenceManager.setDefaultValues(context, R.xml.preferences, true)
        preferences.edit()
            .putBoolean(context.getString(R.string.settings_key_security_backup_warning), true)
            .commit()
        return preferences
    }

    /** Removes the test's preferences and any session the test left behind. */
    fun wipePreferences(context: Context) {
        VaultSession.lock()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    /**
     * Starts an activity the way the launcher would and returns its instance. Unlike
     * ActivityScenario.launch this does not require the activity to reach RESUMED, which matters
     * for the locked-vault flows: MainActivity starts the authentication screen from onResume and
     * never settles in RESUMED until the prompt is answered.
     */
    fun startActivity(context: Context, activityClass: Class<out Activity>): Activity {
        val intent = Intent(context, activityClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return requireNotNull(instrumentation.startActivitySync(intent)) {
            "${activityClass.simpleName} was not started"
        }
    }

    /** Waits for a resumed instance of [T]; null when none appears before the timeout. */
    inline fun <reified T : Activity> awaitResumed(timeoutMillis: Long = 10_000): T? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
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

    /** The simple names of the activities currently on screen, for assertion messages. */
    fun resumedActivityNames(): List<String> {
        var names: List<String> = emptyList()
        instrumentation.runOnMainSync {
            names = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .map { it.javaClass.simpleName }
        }
        return names
    }

    /** True once the activity reports `isFinishing`, read on the main thread. */
    fun awaitFinishing(activity: Activity, timeoutMillis: Long = 10_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var finishing = false
            instrumentation.runOnMainSync { finishing = activity.isFinishing() }
            if (finishing) return true
            SystemClock.sleep(50)
        }
        return false
    }

    /**
     * Finishes every activity the test left on screen and waits for the teardown to complete.
     * Waiting matters: a paused MainActivity keeps its inactivity runnable, which would revoke the
     * session of whichever test runs next.
     */
    fun finishActivities(timeoutMillis: Long = 5_000) {
        instrumentation.waitForIdleSync()
        for (stage in listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED)) {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
                    .toList()
                    .forEach { it.finishAndRemoveTask() }
            }
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (remainingActivityCount() == 0) return
            SystemClock.sleep(50)
        }
    }

    private fun remainingActivityCount(): Int {
        var count = 0
        instrumentation.runOnMainSync {
            count = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED).sumOf {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).size
            }
        }
        return count
    }

    /**
     * The fail-closed flows (failed authentication, screen-off teardown) finish their own
     * activity; ActivityScenario.close() then throws because no state is left to resolve. There is
     * nothing to close in that case, and a failure here would mask the assertions above it.
     */
    fun closeQuietly(scenario: ActivityScenario<*>) {
        try {
            scenario.close()
        } catch (_: RuntimeException) {
            // Already finished by the code under test.
        }
    }
}
