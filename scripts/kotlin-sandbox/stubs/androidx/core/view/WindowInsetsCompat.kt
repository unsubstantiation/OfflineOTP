// Sandbox recording seam only; does not dispatch Android window events.
package androidx.core.view

import android.view.View
import android.view.Window

class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)
class WindowInsetsCompat(val values: Map<Int, Insets> = emptyMap()) {
    fun getInsets(types: Int): Insets {
        val parts = values.filterKeys { it and types != 0 }.values
        return Insets(parts.maxOfOrNull { it.left } ?: 0, parts.maxOfOrNull { it.top } ?: 0,
            parts.maxOfOrNull { it.right } ?: 0, parts.maxOfOrNull { it.bottom } ?: 0)
    }
    object Type {
        fun systemBars() = 1
        fun displayCutout() = 2
        fun ime() = 4
    }
    companion object { val CONSUMED = WindowInsetsCompat() }
}
object ViewCompat {
    private val listeners = java.util.WeakHashMap<View, (View, WindowInsetsCompat) -> WindowInsetsCompat>()
    fun setOnApplyWindowInsetsListener(view: View, listener: (View, WindowInsetsCompat) -> WindowInsetsCompat) { listeners[view] = listener }
    fun requestApplyInsets(view: View) {}
    fun dispatchApplyWindowInsets(view: View, insets: WindowInsetsCompat): WindowInsetsCompat = listeners[view]?.invoke(view, insets) ?: insets
}
object WindowCompat {
    fun setDecorFitsSystemWindows(window: Window, fits: Boolean) {}
}
