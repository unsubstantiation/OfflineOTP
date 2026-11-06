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

package com.fairuniversal.offlineotp.Utilities

import android.text.method.PasswordTransformationMethod
import android.widget.EditText

import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Keeps the password and confirmation fields' masking in sync with the show-password toggle. */
object ConfirmedPasswordTransformationHelper {

    /** Sets up the specified password views for a toggleable obscure/view password text transformation. */
    @JvmStatic
    fun setup(passwordLayout: TextInputLayout, passwordInput: TextInputEditText, passwordConfirmInput: EditText) {
        passwordLayout.setEndIconOnClickListener {
            val wasShowingPassword = passwordInput.transformationMethod is PasswordTransformationMethod
            // Dispatch password visibility change to both password and confirm inputs
            dispatchPasswordVisibilityChange(passwordInput, wasShowingPassword)
            dispatchPasswordVisibilityChange(passwordConfirmInput, wasShowingPassword)
            passwordLayout.refreshDrawableState()
        }
        passwordInput.transformationMethod = PasswordTransformationMethod.getInstance()
        passwordConfirmInput.transformationMethod = PasswordTransformationMethod.getInstance()
    }

    /** Shows or hides the text while keeping the cursor position. */
    private fun dispatchPasswordVisibilityChange(editText: EditText, wasShowingPassword: Boolean) {
        val selection = editText.selectionEnd
        val newMethod = if (wasShowingPassword) null else PasswordTransformationMethod.getInstance()
        editText.transformationMethod = newMethod
        if (selection >= 0) {
            editText.setSelection(selection)
        }
    }
}
