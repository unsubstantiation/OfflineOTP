package com.fairuniversal.offlineotp.Utilities

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** One inset owner for View/Compose hybrid Activities, including Android 15's edge-to-edge windows. */
object ActivityContentInsets {
    fun install(activity: Activity) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false)
        applyTo(content)
    }

    internal fun applyTo(content: View) {
        // Capture once, not on each dispatch: rotation, IME and navigation-mode changes must not
        // accumulate padding. Children (including legacy fitsSystemWindows roots) get consumed
        // insets because this host already keeps both View and Compose content in the safe area.
        val left = content.paddingLeft
        val top = content.paddingTop
        val right = content.paddingRight
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }
}
