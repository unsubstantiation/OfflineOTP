/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 * Copyright (C) 2017-2020 Richy HBM
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Utilities

import android.util.DisplayMetrics
import android.util.TypedValue

import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.regex.Pattern

/** Converts dimension strings such as "12dp" from the preferences to pixels. */
object DimensionConverter {

    /** Maps dimension unit suffixes ("dp", "sp", ...) to [TypedValue] constants. */
    @JvmField
    val dimensionConstantLookup: Map<String, Int> = initDimensionConstantLookup()

    private fun initDimensionConstantLookup(): Map<String, Int> {
        val m = HashMap<String, Int>()
        m["px"] = TypedValue.COMPLEX_UNIT_PX
        m["dip"] = TypedValue.COMPLEX_UNIT_DIP
        m["dp"] = TypedValue.COMPLEX_UNIT_DIP
        m["sp"] = TypedValue.COMPLEX_UNIT_SP
        m["pt"] = TypedValue.COMPLEX_UNIT_PT
        m["in"] = TypedValue.COMPLEX_UNIT_IN
        m["mm"] = TypedValue.COMPLEX_UNIT_MM
        return Collections.unmodifiableMap(m)
    }

    // Pattern for values like "12dp" or "1.5sp" — at most one fractional part (e.g. "1.2.3dp" must not match).
    private val DIMENSION_PATTERN: Pattern = Pattern.compile("^\\s*(\\d+(\\.\\d+)?)\\s*([a-zA-Z]+)\\s*$")

    /** Like [TypedValue.complexToDimensionPixelSize], but parses a string such as "12dp". */
    @JvmStatic
    fun stringToDimensionPixelSize(dimension: String, metrics: DisplayMetrics): Int {
        val internalDimension = stringToInternalDimension(dimension)
        val value = internalDimension.value
        val f = TypedValue.applyDimension(internalDimension.unit, value, metrics)
        val res = (f + 0.5f).toInt()
        if (res != 0) return res
        if (value == 0f) return 0
        return if (value > 0f) 1 else -1
    }

    /** Like [TypedValue.complexToDimension], but parses a string such as "12dp". */
    @JvmStatic
    fun stringToDimension(dimension: String, metrics: DisplayMetrics): Float {
        val internalDimension = stringToInternalDimension(dimension)
        return TypedValue.applyDimension(internalDimension.unit, internalDimension.value, metrics)
    }

    /** Parses a dimension string; throws [NumberFormatException] if it is invalid. */
    private fun stringToInternalDimension(dimension: String): InternalDimension {
        val matcher = DIMENSION_PATTERN.matcher(dimension)
        if (!matcher.matches()) {
            throw NumberFormatException()
        }
        val value = java.lang.Float.valueOf(matcher.group(1)).toFloat()
        // The unit suffix is a machine token: "IN" must not become "ın" under a Turkish locale.
        val unit = matcher.group(3).lowercase(Locale.ROOT)
        val dimensionUnit = dimensionConstantLookup[unit]
        return if (dimensionUnit == null) {
            throw NumberFormatException()
        } else {
            InternalDimension(value, dimensionUnit)
        }
    }

    /** A parsed dimension: its value and the matching TypedValue unit. */
    private class InternalDimension(val value: Float, val unit: Int)
}
