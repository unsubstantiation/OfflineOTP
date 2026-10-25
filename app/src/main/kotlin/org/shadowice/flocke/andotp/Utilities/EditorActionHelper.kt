package org.shadowice.flocke.andotp.Utilities

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/** Normalizes the IME "done"/"enter" editor actions across the different editors. */
object EditorActionHelper {
    /**
     * True for [EditorInfo.IME_ACTION_DONE] or a hardware Enter key-down [event].
     *
     * [event] is null for actions reported by the IME itself, so it is only inspected when
     * [actionId] is not [EditorInfo.IME_ACTION_DONE].
     */
    @JvmStatic
    fun isActionDoneOrKeyboardEnter(actionId: Int, event: KeyEvent?): Boolean {
        if (actionId == EditorInfo.IME_ACTION_DONE) return true
        val keyEvent = event ?: return false
        return keyEvent.action == KeyEvent.ACTION_DOWN &&
                (keyEvent.keyCode == KeyEvent.KEYCODE_ENTER || keyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
    }

    /** True for a hardware Enter key-up [event]; returns false when [event] is null (IME actions). */
    @JvmStatic
    fun isActionUpKeyboardEnter(event: KeyEvent?): Boolean {
        val keyEvent = event ?: return false
        return keyEvent.action == KeyEvent.ACTION_UP &&
                (keyEvent.keyCode == KeyEvent.KEYCODE_ENTER || keyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}
