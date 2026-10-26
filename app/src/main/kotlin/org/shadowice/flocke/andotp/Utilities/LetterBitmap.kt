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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue

import org.shadowice.flocke.andotp.R

/**
 * Original: http://stackoverflow.com/questions/23122088/colored-boxed-with-letters-a-la-gmail
 * Draws the first letter/digit, or a "?" placeholder. Created afresh for each thumbnail request.
 */
internal open class LetterBitmap(context: Context) {
    private val paint = TextPaint().apply {
        setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD))
        color = Color.WHITE
        setTextAlign(Paint.Align.CENTER)
        isAntiAlias = true
    }
    private val bounds = Rect()
    private val canvas = Canvas()
    private val colors: IntArray = run {
        val typedArray = context.resources.obtainTypedArray(R.array.letter_tile_colors)
        try {
            IntArray(typedArray.length()) { i -> typedArray.getColor(i, Color.BLACK) }
        } finally {
            typedArray.recycle()
        }
    }
    private val tileLetterFontSizeScale: Float

    init {
        val typedValue = TypedValue()
        context.resources.getValue(R.dimen.tile_letter_font_size_scale, typedValue, true)
        tileLetterFontSizeScale = typedValue.getFloat()
    }

    /** Returns a tile at the requested dimensions; invalid dimensions are rejected by Bitmap. */
    open fun getLetterTile(displayName: String, key: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val codePoint = if (displayName.isNotEmpty() && startsWithAlphabeticOrDigit(displayName)) {
            Character.toUpperCase(displayName.codePointAt(0))
        } else {
            '?'.code
        }
        val glyph = Character.toChars(codePoint)

        canvas.setBitmap(bitmap)
        canvas.drawColor(pickColor(key))
        paint.textSize = tileLetterFontSizeScale * height
        paint.getTextBounds(glyph, 0, glyph.size, bounds)
        // Integer centering for odd dimensions and odd glyph bounds.
        canvas.drawText(glyph, 0, glyph.size, (width / 2).toFloat(),
            (height / 2 + (bounds.bottom - bounds.top) / 2).toFloat(), paint)
        return bitmap
    }

    private fun pickColor(key: String): Int {
        // The loaded palette defines the valid range: a hard-coded count silently drifts from
        // R.array.letter_tile_colors, and `Math.abs` returns a negative index for
        // Integer.MIN_VALUE whenever the palette length does not divide it. floorMod maps every
        // hash into [0, colors.size).
        if (colors.isEmpty()) return Color.BLACK
        return colors[Math.floorMod(key.hashCode(), colors.size)]
    }

    private companion object {
        fun startsWithAlphabeticOrDigit(string: String): Boolean {
            val firstCodePoint = string.codePointAt(0)
            return Character.isAlphabetic(firstCodePoint) || Character.isDigit(firstCodePoint)
        }
    }
}
