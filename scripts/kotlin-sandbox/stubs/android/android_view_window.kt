// Sandbox-only stand-ins, see android_content.kt.
package android.view

/**
 * Stand-in for the platform window.
 *
 * It only records what the converted activities did with their window: the navigation bar color,
 * the window flags and the insets controller. There is no window, decor, insets or system bar
 * behaviour here.
 */
open class Window {
    /** The window's root view: the decor the platform places the activity content in. */
    open val decorView: View = View()
    /** The navigation bar color that was set. */
    var navigationBarColor: Int = 0
        private set

    /** The flag calls that were made, in call order: the flags and the mask. */
    val flagCalls = ArrayList<Pair<Int, Int>>()

    /** The insets controller of this window; the platform creates it with the window. */
    var windowInsetsController: WindowInsetsController? = null

    /** The soft input mode that was requested. */
    var softInputMode: Int = 0
        private set

    open fun setNavigationBarColor(color: Int) {
        navigationBarColor = color
    }

    /** Records the soft input mode; the platform applies it when the window is shown. */
    open fun setSoftInputMode(mode: Int) {
        softInputMode = mode
    }

    /** Records the flags; the platform turns the masked bits on or off. */
    open fun setFlags(flags: Int, mask: Int) {
        flagCalls.add(flags to mask)
    }

    /** The controller of the system bars; the platform returns null before the window exists. */
    open fun getInsetsController(): WindowInsetsController? = windowInsetsController
}

/**
 * Stand-in for the window manager. Only the window flag constants are used; the platform class
 * also carries the layout parameters, the gravity and the pixel format.
 */
open class WindowManager {
    /** The window parameters; only the flag constants are used here. */
    class LayoutParams {
        companion object {
            /** The platform's flag values. */
            const val FLAG_FULLSCREEN: Int = 0x00000400
            const val FLAG_SECURE: Int = 0x00002000
            const val FLAG_HARDWARE_ACCELERATED: Int = 0x01000000
            /** The soft input state constants the authenticate screen uses. */
            const val SOFT_INPUT_STATE_ALWAYS_VISIBLE: Int = 0x00000005
            const val SOFT_INPUT_STATE_HIDDEN: Int = 0x00000004
            /** The resize mode the settings/entry dialogs request (#805). */
            const val SOFT_INPUT_ADJUST_RESIZE: Int = 0x00000010
        }
    }
}
