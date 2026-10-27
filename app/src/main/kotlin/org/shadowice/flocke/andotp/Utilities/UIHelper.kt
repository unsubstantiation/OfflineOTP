/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
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

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager

/** Small helpers for generic dialogs and the soft keyboard. */
object UIHelper {
    @JvmStatic
    fun showGenericDialog(context: Context, titleId: Int, messageId: Int) {
        showGenericDialog(context, titleId, messageId, null)
    }

    /** Shows a message dialog, running [onOk] when it is confirmed. */
    @JvmStatic
    fun showGenericDialog(context: Context, titleId: Int, messageId: Int, onOk: Runnable?) {
        AlertDialog.Builder(context)
                .setTitle(titleId)
                .setMessage(messageId)
                .setPositiveButton(android.R.string.ok) { _, _ -> onOk?.run() }
                .create()
                .show()
    }

    @JvmStatic
    fun showKeyboard(context: Context, view: View?) {
        showKeyboard(context, view, false)
    }

    /** Shows the soft keyboard for [view] (forced when [showForced] is true). */
    @JvmStatic
    fun showKeyboard(context: Context, view: View?, showForced: Boolean?) {
        if (view == null) {
            return
        }

        val forced = showForced ?: throw NullPointerException("Missing showForced flag")
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (forced) {
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
        } else {
            imm.showSoftInput(view, 0)
        }
    }

    /** Hides the soft keyboard of the given view. */
    @JvmStatic
    fun hideKeyboard(context: Context, view: View?) {
        if (view != null) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}
