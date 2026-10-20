// Sandbox-only stand-ins, see ../../../../android/android_content.kt.
package com.leinardi.android.speeddial

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** An action item of the speed dial. The platform identifies it with getId(). */
class SpeedDialActionItem(private val id: Int) {
    fun getId(): Int = id
}

/** Child row that wraps the accessible action button. */
class FabWithLabelView : View() {
    private val fab = View()
    fun getFab(): View = fab
}

/** The floating action button menu of the main screen. */
open class SpeedDialView(context: Context) : ViewGroup() {
    private val mainFab = View()
    private val actionItems = ArrayList<SpeedDialActionItem>()

    /** The action listener that was set. */
    var onActionSelectedListener: OnActionSelectedListener? = null
        private set

    /** The change listener that was set. */
    var onChangeListener: OnChangeListener? = null
        private set

    /** Whether the menu is open. */
    var open: Boolean = false
        private set

    /** The menu resource that was inflated. */
    var inflatedResource = 0
        private set

    fun inflate(resId: Int) {
        inflatedResource = resId
    }

    fun getMainFab(): View = mainFab

    fun getActionItems(): ArrayList<SpeedDialActionItem> = ArrayList(actionItems)

    fun setOnActionSelectedListener(listener: OnActionSelectedListener?) {
        onActionSelectedListener = listener
    }

    fun setOnChangeListener(listener: OnChangeListener?) {
        onChangeListener = listener
    }

    fun isOpen(): Boolean = open

    fun open() {
        open = true
    }

    fun close() {
        open = false
    }

    /** The action callbacks of the speed dial. */
    fun interface OnActionSelectedListener {
        fun onActionSelected(item: SpeedDialActionItem): Boolean
    }

    /** The state callbacks of the speed dial. */
    interface OnChangeListener {
        fun onMainActionSelected(): Boolean
        fun onToggleChanged(isOpen: Boolean)
    }
}
