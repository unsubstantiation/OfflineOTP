/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Assertions for real Android drawables and widget colors. Host sandbox stubs do not render them.
 */
package org.shadowice.flocke.andotp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.pow

internal object ThemeDeviceTestSupport {
    fun surface(context: Context): Int {
        // This resource is otherwise only referenced from XML; resolve it at runtime so the
        // compile-only sandbox's source-derived R stand-in does not pretend to process XML.
        val id = context.resources.getIdentifier("forest_surface_elevated", "color", context.getPackageName())
        check(id != 0) { "missing elevated forest surface color" }
        return ContextCompat.getColor(context, id)
    }

    /** Samples the actual window background, not just the colorBackground theme attribute. */
    fun assertDialogSurface(label: String, window: Window?, context: Context) {
        val drawable = requireNotNull(requireNotNull(window).decorView.background) { "$label has no background" }
        assertDrawableColor(label, drawable, surface(context), context)
    }

    fun assertDrawableColor(label: String, drawable: Drawable, expected: Int, context: Context) {
        // Restore the original bounds; the Drawable may also be installed on a visible view.
        val bounds = drawable.bounds
        val left = bounds.left
        val top = bounds.top
        val right = bounds.right
        val bottom = bounds.bottom
        val size = (128 * context.resources.displayMetrics.density).toInt().coerceAtLeast(128)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            assertEquals("$label has the wrong center color", expected, bitmap.getPixel(size / 2, size / 2))
        } finally {
            drawable.setBounds(left, top, right, bottom)
            bitmap.recycle()
        }
    }

    fun assertTextReadable(label: String, text: TextView, background: Int, hint: Boolean = false) {
        val foreground = if (hint) text.currentHintTextColor else text.currentTextColor
        val ratio = contrast(foreground, background)
        assertTrue("$label contrast was $ratio; expected at least 4.5:1", ratio >= 4.5)
    }

    fun firstText(view: View): TextView? {
        if (view is TextView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = firstText(view.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    fun <T : View> firstView(view: View, kind: Class<T>): T? {
        if (kind.isInstance(view)) return kind.cast(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = firstView(view.getChildAt(index), kind)
                if (found != null) return found
            }
        }
        return null
    }

    // TextView colors can have alpha. Composite them over the surface before computing WCAG
    // relative luminance, so a translucent white cannot pass merely because its RGB is white.
    private fun contrast(foreground: Int, background: Int): Double {
        val alpha = (foreground ushr 24 and 0xff) / 255.0
        fun component(color: Int, shift: Int) = (color ushr shift and 0xff) / 255.0
        fun luminance(red: Double, green: Double, blue: Double): Double {
            fun linear(value: Double) = if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
        }
        val bg = listOf(16, 8, 0).map { component(background, it) }
        val fg = listOf(16, 8, 0).mapIndexed { index, shift ->
            component(foreground, shift) * alpha + bg[index] * (1.0 - alpha)
        }
        val l1 = luminance(fg[0], fg[1], fg[2])
        val l2 = luminance(bg[0], bg[1], bg[2])
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}
