// Sandbox-only stand-ins, see android_content.kt.
package android.view

import android.content.Context
import android.os.IBinder

/**
 * `postDelayed` is left `open` here purely so the checks can capture what a listener posts; the
 * platform method is not overridable in the same way. No platform timing is exercised. The
 * alpha/translation/width/windowToken surface exists for the view-drawing and keyboard helpers;
 * the platform getters are final and the fakes subclass this stand-in instead. The id/visibility/
 * enable/click surface lets the dialog checks drive recording widgets the way the platform would.
 */
open class View {
    /** The platform interface is Java; a `fun interface` keeps lambda/SAM conversion working. */
    fun interface OnClickListener {
        fun onClick(v: View)
    }

    var id: Int = 0
    var layoutParams: ViewGroup.LayoutParams? = null
    var contentDescription: CharSequence? = null
    var alpha: Float = 1.0f
    var translationX: Float = 0.0f
    /** The platform getter is final; the stand-in is writable so checks can size a fake view. */
    var width: Int = 0
    /** The platform getter is final; the stand-in is writable so checks can size a fake view. */
    var height: Int = 0
    var visibility: Int = VISIBLE
    var isEnabled: Boolean = true
    /** Recorded by the setImportantFor* methods below; distinct names avoid the setter clash. */
    var importantForAccessibilityMode: Int = 0
    var importantForAutofillMode: Int = 0
    val windowToken: IBinder? get() = null
    /** The background drawable; the converted code reads it back to copy it onto another view. */
    var background: android.graphics.drawable.Drawable? = null
    /** The tag the card view holder stores the unformatted token in. */
    var tag: Any? = null
    /** Whether the view saves its state; the credential fields disable it. */
    var isSaveEnabled: Boolean = true

    /** The context the view is inflated in. */
    open val context: Context = Context()
    /** The drawable state the text colour lookup is resolved against. */
    var drawableState: IntArray = intArrayOf()

    private var clickListener: OnClickListener? = null
    private var longClickListener: OnLongClickListener? = null

    open fun refreshDrawableState() {}

    open fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean = true
    open fun post(runnable: Runnable): Boolean = true
    open fun removeCallbacks(runnable: Runnable): Boolean = true
    open fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean = true

    /** Records the listener so `performClick` can dispatch to it like the platform would. */
    open fun setOnClickListener(listener: OnClickListener?) {
        clickListener = listener
    }

    /** Records the long-click listener; the card counter uses it. */
    open fun setOnLongClickListener(listener: OnLongClickListener?) {
        longClickListener = listener
    }

    open fun performClick(): Boolean {
        val listener = clickListener ?: return false
        listener.onClick(this)
        return true
    }

    /** Dispatches to the recorded long-click listener. */
    open fun performLongClick(): Boolean {
        val listener = longClickListener ?: return false
        return listener.onLongClick(this)
    }

    open fun requestFocus(): Boolean = true
    open fun requestLayout() {}
    open fun setBackgroundColor(color: Int) {}
    open fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {}

    fun setPaddingRelative(start: Int, top: Int, end: Int, bottom: Int) {}
    fun setImportantForAccessibility(mode: Int) {
        importantForAccessibilityMode = mode
    }

    fun getImportantForAccessibility(): Int = importantForAccessibilityMode

    fun setImportantForAutofill(mode: Int) {
        importantForAutofillMode = mode
    }

    /** The platform interface is Java; a `fun interface` keeps lambda/SAM conversion working. */
    fun interface OnLongClickListener {
        fun onLongClick(v: View): Boolean
    }

    /**
     * Inert lookup; `AppCompatDialog` routes through a scripted provider for the dialog checks,
     * and `open` so the about checks can hand out scripted children of an inflated layout.
     */
    open fun <T : View> findViewById(id: Int): T? = null

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val IMPORTANT_FOR_ACCESSIBILITY_AUTO = 0
        const val IMPORTANT_FOR_ACCESSIBILITY_YES = 1
        const val IMPORTANT_FOR_ACCESSIBILITY_NO = 2
        const val IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 4
        const val IMPORTANT_FOR_AUTOFILL_NO = 2
        const val IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS = 8
    }
}

class KeyEvent(private val eventAction: Int, private val eventKeyCode: Int) {
    val action: Int get() = eventAction
    val keyCode: Int get() = eventKeyCode

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val KEYCODE_BACK = 4
        const val KEYCODE_ENTER = 66
        const val KEYCODE_NUMPAD_ENTER = 160
    }
}

/** Inert layout stand-ins: no Android measurement or inflation happens here. */
open class ViewGroup : View() {
    open class LayoutParams(var width: Int, var height: Int) {
        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }

    private val children = mutableListOf<View>()

    /** Records the child; no measurement, layout or drawing is implemented. */
    open fun addView(view: View) {
        children.add(view)
    }

    /** Records the child with its layout parameters. */
    open fun addView(view: View, params: LayoutParams) {
        children.add(view)
        view.layoutParams = params
    }

    open fun getChildAt(index: Int): View = children[index]

    open val childCount: Int get() = children.size
}

class LayoutInflater private constructor() {
    fun inflate(resource: Int, parent: ViewGroup?, attachToRoot: Boolean): View =
        layoutProvider?.invoke(resource, parent, attachToRoot) ?: android.widget.CheckedTextView()

    /** The two-argument overload; the platform attaches to `parent` only when it is non-null. */
    fun inflate(resource: Int, parent: ViewGroup?): View =
        layoutProvider?.invoke(resource, parent, parent != null) ?: android.widget.CheckedTextView()

    companion object {
        /**
         * Scripted inflation for the checks; the platform resolves XML layouts instead. Nothing in
         * production sets this.
         */
        var layoutProvider: ((Int, ViewGroup?, Boolean) -> View)? = null

        @JvmStatic fun from(context: android.content.Context): LayoutInflater = LayoutInflater()
    }
}

/**
 * Inflates the real layout in its place when a screen is created. `inflate` is `open` so the
 * checks can return a scripted root with scripted children; the platform replaces the stub view
 * in the hierarchy instead.
 */
open class ViewStub : View() {
    private var layoutResource = 0

    open fun setLayoutResource(layoutResId: Int) {
        layoutResource = layoutResId
    }

    open fun getLayoutResource(): Int = layoutResource

    open fun inflate(): View = android.widget.CheckedTextView()
}

object Gravity { const val CENTER = 17 }
