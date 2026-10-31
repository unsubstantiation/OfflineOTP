package org.shadowice.flocke.andotp.Utilities

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.PersistableBundle
import android.view.Window
import android.view.WindowManager
import android.widget.Toast

import org.shadowice.flocke.andotp.R

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Small helpers for theming, token formatting and the clipboard. */
object Tools {
    /** Resolves a theme color attribute to its color value. */
    @JvmStatic fun getThemeColor(context: Context, colorAttr: Int): Int {
        val theme = context.theme
        val arr = theme.obtainStyledAttributes(intArrayOf(colorAttr))

        val colorValue = arr.getColor(0, -1)
        arr.recycle()

        return colorValue
    }

    /** Resolves a theme attribute to a resource ID. */
    @JvmStatic fun getThemeResource(context: Context, styleAttr: Int): Int {
        val theme = context.theme
        val arr = theme.obtainStyledAttributes(intArrayOf(styleAttr))

        val styleValue = arr.getResourceId(0, -1)
        arr.recycle()

        return styleValue
    }

    /** A color filter for tinting icons with the current theme color. */
    @JvmStatic fun getThemeColorFilter(context: Context, colorAttr: Int): ColorFilter {
        return PorterDuffColorFilter(getThemeColor(context, colorAttr), PorterDuff.Mode.SRC_IN)
    }

    @JvmStatic fun getSystemLocale(): Locale {
        return Resources.getSystem().configuration.locales[0]
    }

    /** Formats the token with leading zeros (without grouping separators). */
    @JvmStatic
    fun formatTokenString(token: Int, digits: Int): String = TokenFormat.formatTokenString(token, digits)

    /** Splits the token into groups of [chunkSize] characters. */
    @JvmStatic fun formatToken(s: String?, chunkSize: Int): String? {
        // A non-positive chunk never shrinks the index below zero and would loop forever.
        if (chunkSize <= 0 || s == null) return s

        val ret = StringBuilder()
        var index = s.length
        while (index > 0) {
            ret.insert(0, s.substring(Math.max(index - chunkSize, 0), index))
            ret.insert(0, " ")
            index -= chunkSize
        }
        return ret.toString().trim()
    }

    /** The current date/time in the format used for backup file names. */
    @JvmStatic fun getDateTimeString(): String {
        val df = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ENGLISH)
        val now = Calendar.getInstance().time
        return df.format(now)
    }

    /**
     * Applies the policy for a window that is (about to be) on screen: screenshots are blocked
     * unless the user has explicitly enabled them.
     */
    @JvmStatic fun applyScreenshotPolicy(window: Window, screenshotsEnabled: Boolean) {
        setSecure(window, ScreenshotPolicy.secureWhileVisible(screenshotsEnabled))
    }

    /**
     * Applies the policy for a window that is leaving the foreground, which is when the system
     * records the app switcher preview. The preview is kept unless the user opted into hiding it
     * (`ScreenshotPolicy.secureWhileBackgrounded`).
     */
    @JvmStatic fun applyAppSwitcherPolicy(
        window: Window,
        screenshotsEnabled: Boolean,
        hideInAppSwitcher: Boolean
    ) {
        setSecure(window, ScreenshotPolicy.secureWhileBackgrounded(screenshotsEnabled, hideInAppSwitcher))
    }

    /** `FLAG_SECURE` is the only switch Android offers for both screenshots and the switcher. */
    @JvmStatic fun setSecure(window: Window, secure: Boolean) {
        if (secure)
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /**
     * Copies the text to the clipboard and shows a confirmation toast.
     * Marks the clip sensitive for supported system previews; other clipboard consumers may still read it.
     */
    @JvmStatic fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(context.getString(R.string.label_clipboard_content), text)
        val extras = PersistableBundle()
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        clip.description.extras = extras
        clipboard.setPrimaryClip(clip)

        Toast.makeText(context, R.string.toast_copied_to_clipboard, Toast.LENGTH_LONG).show()
    }
}
