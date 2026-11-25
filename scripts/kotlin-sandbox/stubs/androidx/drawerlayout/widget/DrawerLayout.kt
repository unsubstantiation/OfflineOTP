// Sandbox-only stand-ins, see ../../../android/android_content.kt.
package androidx.drawerlayout.widget

import android.view.View
import android.view.ViewGroup

/** The tag drawer of the main screen. */
open class DrawerLayout : ViewGroup() {
    var drawerLockMode: Int = 0
        private set
    fun setDrawerLockMode(mode: Int) { drawerLockMode = mode }
    companion object { const val LOCK_MODE_LOCKED_CLOSED = 1 }

    /** The listeners that were added, in order. */
    val listeners: MutableList<DrawerListener> = ArrayList()

    /** The drawers that are open, by gravity. */
    val openDrawers: MutableSet<Int> = LinkedHashSet()

    fun addDrawerListener(listener: DrawerListener) {
        listeners.add(listener)
    }

    fun isDrawerOpen(gravity: Int): Boolean = openDrawers.contains(gravity)

    fun closeDrawer(gravity: Int) {
        openDrawers.remove(gravity)
    }

    fun openDrawer(gravity: Int) {
        openDrawers.add(gravity)
    }

    /** The drawer callbacks. */
    interface DrawerListener {
        fun onDrawerOpened(drawerView: View)
        fun onDrawerClosed(drawerView: View)
        fun onDrawerSlide(drawerView: View, slideOffset: Float)
        fun onDrawerStateChanged(newState: Int)
    }
}
