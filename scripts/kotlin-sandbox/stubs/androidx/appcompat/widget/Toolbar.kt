// Sandbox-only stand-ins, see ../../../android/android_content.kt.
package androidx.appcompat.widget

import android.view.ViewGroup

/** Inert toolbar; the converted about screen only installs it as the support action bar. */
open class Toolbar(context: android.content.Context) : ViewGroup() {
    /** The navigation icon; the authenticate screen removes it. */
    var navigationIcon: android.graphics.drawable.Drawable? = null
        private set

    open fun setNavigationIcon(icon: android.graphics.drawable.Drawable?) {
        navigationIcon = icon
    }
}
