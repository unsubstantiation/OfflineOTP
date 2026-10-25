/*
 * Copyright (C) 2020 Jakob Nixdorf
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

package org.shadowice.flocke.andotp.View

import android.os.SystemClock
import android.view.View

/** Distinguishes single and double taps, delaying single-tap delivery by 300 ms. */
abstract class SimpleDoubleClickListener : View.OnClickListener {
    private val taps = TapSequenceController<View>(
        nowMillis = { SystemClock.uptimeMillis() },
        postDelayed = { view, callback, delay -> view.postDelayed(callback, delay) },
        removeCallbacks = { view, callback -> view.removeCallbacks(callback) },
        onSingleClick = ::onSingleClick,
        onDoubleClick = ::onDoubleClick
    )

    override fun onClick(v: View) {
        taps.onClick(v)
    }

    /** Cancels any pending delayed single-tap delivery (e.g. when a RecyclerView row is recycled or rebound). */
    fun cancelPending() {
        taps.cancelPending()
    }

    abstract fun onSingleClick(v: View)
    abstract fun onDoubleClick(v: View)
}
