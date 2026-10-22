/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Regression coverage for andOTP #406 ("crash when clicking away the BitWarden safe dialog").
 * Vault activities reject touches the system marks as obscured instead of acting on a tap that
 * belongs to another window. This drives BaseActivity.dispatchTouchEvent with real platform
 * MotionEvents built through the public obtain(...) overload that carries the flag word, and
 * proves the same tap is delivered when no obscuring flag is present.
 *
 * The tap target is a view the test adds on top of the content frame: it is laid out explicitly,
 * so the hit test cannot depend on whichever child of AboutActivity happens to sit under the
 * centre of the screen.
 */
package org.shadowice.flocke.andotp

import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clicks = 0
        var delivered = false
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            var activity: AboutActivity? = null
            scenario.onActivity { activity = it }
            instrumentation.runOnMainSync {
                val target = addTapTarget(requireNotNull(activity)) { clicks++ }
                val (x, y) = centreOf(target)
                val down = dispatch(requireNotNull(activity), 0, MotionEvent.ACTION_DOWN, x, y)
                val up = dispatch(requireNotNull(activity), 0, MotionEvent.ACTION_UP, x, y)
                delivered = down && up
            }
            // View.onTouchEvent posts the click runnable; drain the queue before asserting.
            instrumentation.waitForIdleSync()
            assertTrue("a normal tap was not delivered", delivered)
            assertEquals("a normal tap did not reach the click listener", 1, clicks)
        }
    }

    @Test
    fun aFullyObscuredTapIsRejected() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var clicks = 0
                val target = addTapTarget(activity) { clicks++ }
                val (x, y) = centreOf(target)

                assertFalse("an obscured tap reached the view hierarchy",
                    dispatch(activity, MotionEvent.FLAG_WINDOW_IS_OBSCURED,
                        MotionEvent.ACTION_DOWN, x, y))
                assertEquals("an obscured tap-down was dispatched", 0, clicks)
            }
        }
    }

    @Test
    fun aPartiallyObscuredTapIsRejected() {
        assumeTrue("partial obscuring exists from Android 10", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var clicks = 0
                val target = addTapTarget(activity) { clicks++ }
                val (x, y) = centreOf(target)

                // The flag constant is API 29; the guard keeps the lint NewApi/InlinedApi gate happy.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    assertFalse("a partially obscured tap reached the view hierarchy",
                        dispatch(activity, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED,
                            MotionEvent.ACTION_DOWN, x, y))
                    assertEquals("a partially obscured tap-down was dispatched", 0, clicks)
                }
            }
        }
    }

    /** Adds a transparent, clickable cover on top of the activity's content frame. */
    private fun addTapTarget(activity: AboutActivity, onClick: () -> Unit): View {
        val content = requireNotNull(activity.findViewById<ViewGroup>(android.R.id.content))
        val target = View(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { onClick() }
        }
        content.addView(target, ViewGroup.LayoutParams(TARGET_SIZE, TARGET_SIZE))
        val spec = View.MeasureSpec.makeMeasureSpec(TARGET_SIZE, View.MeasureSpec.EXACTLY)
        target.measure(spec, spec)
        target.layout(0, 0, TARGET_SIZE, TARGET_SIZE)
        return target
    }

    /** The middle of the target in window coordinates, which is what dispatchTouchEvent takes. */
    private fun centreOf(target: View): Pair<Float, Float> {
        val location = IntArray(2)
        target.getLocationInWindow(location)
        return Pair(location[0] + TARGET_SIZE / 2f, location[1] + TARGET_SIZE / 2f)
    }

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

    private companion object {
        const val TARGET_SIZE = 240
    }
}
