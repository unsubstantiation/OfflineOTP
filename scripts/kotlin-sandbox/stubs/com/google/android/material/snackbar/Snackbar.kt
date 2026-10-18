// Sandbox-only stand-ins, see ../../../../../android/android_content.kt.
package com.google.android.material.snackbar

import android.view.View

/** The base of the snackbars; only the duration constants are used. */
open class BaseTransientBottomBar {
    companion object {
        const val LENGTH_SHORT = -1
        const val LENGTH_LONG = 0
        const val LENGTH_INDEFINITE = -2
    }
}

/** The snackbar of the settings screen; the stand-in records the message. */
open class Snackbar : BaseTransientBottomBar() {
    /** The message that was shown. */
    var message: Int = 0
        private set

    /** Whether the snackbar was shown. */
    var shown = false
        private set

    /** Whether the snackbar was dismissed. */
    var dismissed = false
        private set

    fun show() {
        shown = true
    }

    fun dismiss() {
        dismissed = true
    }

    companion object {
        /** The snackbars that were made, in order. */
        @JvmField
        val created: MutableList<Snackbar> = ArrayList()

        @JvmStatic
        fun make(view: View?, message: Int, duration: Int): Snackbar {
            val snackbar = Snackbar()
            snackbar.message = message
            created.add(snackbar)
            return snackbar
        }
    }
}
