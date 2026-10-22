/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Regression coverage for andOTP #406 ("crash when clicking away the BitWarden safe dialog").
 * Vault activities reject touches the system marks as obscured instead of acting on a tap that
 * belongs to another window. This drives BaseActivity.dispatchTouchEvent with real platform
 * MotionEvents built through the public obtain(...) overload that carries the flag word, and
 * proves the same tap is delivered when no obscuring flag is present.
 */
package org.shadowice.flocke.andotp

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.AboutActivity

@RunWith(AndroidJUnit4::class)
class ObscuredTouchDeviceTest {
    @Test
    fun aNormalTapIsDeliveredAndClicks() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var clicks = 0
                val content = clickableContent(activity) { clicks++ }
                val x = centerX(activity, content)
                val y = centerY(activity, content)

                assertTrue("a normal tap was not delivered",
                    dispatch(activity, 0, MotionEvent.ACTION_DOWN, x, y))
                assertTrue("a normal tap-up was not delivered",
                    dispatch(activity, 0, MotionEvent.ACTION_UP, x, y))
                assertEquals("a normal tap did not reach the click listener", 1, clicks)
            }
        }
    }

    @Test
    fun aFullyObscuredTapIsRejected() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var clicks = 0
                val content = clickableContent(activity) { clicks++ }

                assertFalse("an obscured tap reached the view hierarchy",
                    dispatch(activity, MotionEvent.FLAG_WINDOW_IS_OBSCURED,
                        MotionEvent.ACTION_DOWN, centerX(activity, content), centerY(activity, content)))
                assertEquals("an obscured tap-up was dispatched", 0, clicks)
            }
        }
    }

    @Test
    fun aPartiallyObscuredTapIsRejected() {
        assumeTrue("partial obscuring exists from Android 10", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var clicks = 0
                val content = clickableContent(activity) { clicks++ }

                assertFalse("a partially obscured tap reached the view hierarchy",
                    dispatch(activity, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED,
                        MotionEvent.ACTION_DOWN, centerX(activity, content), centerY(activity, content)))
                assertEquals("a partially obscured tap-up was dispatched", 0, clicks)
            }
        }
    }

    /** Replaces nothing: it makes the existing content frame the tap target, like a clickable row. */
    private fun clickableContent(activity: AboutActivity, onClick: () -> Unit): View {
        val content = requireNotNull(activity.findViewById<View>(android.R.id.content))
        content.setOnClickListener { onClick() }
        return content
    }

    /** The middle of the content frame, or of the screen before it has been laid out. */
    private fun centerX(activity: AboutActivity, content: View): Float =
        if (content.width > 0) content.width / 2f
        else activity.resources.displayMetrics.widthPixels / 2f

    private fun centerY(activity: AboutActivity, content: View): Float =
        if (content.height > 0) content.height / 2f
        else activity.resources.displayMetrics.heightPixels / 2f

    private fun dispatch(activity: AboutActivity, flags: Int, action: Int, x: Float, y: Float): Boolean {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 1, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
        return try {
            activity.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
