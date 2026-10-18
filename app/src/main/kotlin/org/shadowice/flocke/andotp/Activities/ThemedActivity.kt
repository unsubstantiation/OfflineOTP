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

package org.shadowice.flocke.andotp.Activities

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.LocaleContextHelper
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.Tools

import java.util.Locale

/** Base activity that applies the configured theme and locale. */
abstract class ThemedActivity : AppCompatActivity() {
    /** Activity-scoped settings instance, initialized in [onCreate]. */
    @JvmField
    var settings: Settings? = null

    override fun attachBaseContext(newBase: Context) {
        val locale = Settings(newBase).getLocale()
        super.attachBaseContext(LocaleContextHelper.createLocalizedContext(newBase, locale))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val current = Settings(this)
        settings = current

        setTheme(current.getTheme())

        // Match the navigation bar to the theme colors
        getWindow().setNavigationBarColor(Tools.getThemeColor(this, R.attr.navigationBarColor))

        super.onCreate(savedInstanceState)
    }

    public override fun onResume() {
        super.onResume()
        setLocale()
    }

    /** Recreates this activity when the selected language changed while it was backgrounded. */
    public fun setLocale() {
        val locale: Locale = settings!!.getLocale()
        Locale.setDefault(locale)
        if (!LocaleContextHelper.isLocaleApplied(this, locale))
            recreate()
    }
}
