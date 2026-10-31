/*
 * Copyright (C) 2019-2021 Jakob Nixdorf
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
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

import org.shadowice.flocke.andotp.Utilities.LocaleContextHelper
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.Tools

/** QR scanner activity that applies the app's theme, locale, and screenshot policy. */
open class SecureCaptureActivity : CaptureActivity() {
    override fun attachBaseContext(newBase: Context) {
        val locale = Settings(newBase).getLocale()
        super.attachBaseContext(LocaleContextHelper.createLocalizedContext(newBase, locale))
    }

    /** Applies theme and window flags around [CaptureActivity.initializeContent] and hides the system bars. */
    override fun initializeContent(): DecoratedBarcodeView {
        val settings = Settings(this)

        setTheme(settings.getTheme())

        // Must be set before super.initializeContent() inflates the content view.
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        val barcodeScannerView = super.initializeContent()

        // The live camera feed and any QR code in it are never left in the app switcher, whether or
        // not the user allowed screenshots.
        Tools.setSecure(getWindow(), true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insetsController = getWindow().getInsetsController()
            if (insetsController != null) {
                insetsController.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                insetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
            }
        } else {
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        return barcodeScannerView
    }

    public override fun onResume() {
        super.onResume()
        Tools.setSecure(getWindow(), true)
        val locale = Settings(this).getLocale()
        if (!LocaleContextHelper.isLocaleApplied(this, locale))
            recreate()
    }

    public override fun onPause() {
        // Compared with the rest of the app the scanner has the opposite default, because the
        // preview would otherwise be the last frame the switcher shows.
        Tools.setSecure(getWindow(), true)
        super.onPause()
    }
}
