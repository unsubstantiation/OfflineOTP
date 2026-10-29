// Sandbox-only stand-ins, see android_content.kt.
package android.widget

import android.content.Context
import android.text.Editable
import android.text.SimpleEditable
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.TransformationMethod
import android.view.KeyEvent
import android.view.View

/** Records the resource id of every toast shown, so checks can assert the user-facing message. */
class Toast private constructor(private val resId: Int, private val text: CharSequence?) {
    /** Whether `cancel()` hid this toast before it was shown again. */
    var cancelled = false
        private set

    fun show() {
        if (resId != 0) shown.add(resId)
        if (text != null) shownTexts.add(text)
    }

    fun cancel() {
        cancelled = true
        cancels++
    }

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @JvmField
        val shown: MutableList<Int> = ArrayList()

        /** The text overload's messages, in show order. */
        @JvmField
        val shownTexts: MutableList<CharSequence> = ArrayList()

        /** How often any toast was cancelled before being shown. */
        @JvmField
        var cancels = 0

        @JvmStatic
        fun makeText(context: Context, resId: Int, duration: Int): Toast = Toast(resId, null)

        @JvmStatic
        fun makeText(context: Context, text: CharSequence, duration: Int): Toast = Toast(0, text)
    }
}

open class TextView : View() {
    /**
     * The text of the view.
     *
     * The platform getter is `getText()`/`setText(CharSequence)`, but `EditText` narrows the
     * getter to `Editable`, which turns the synthetic Kotlin property into a read-only one there.
     * The stand-in therefore models the text as a read-only property plus the `setText` method,
     * which is the strict shape every receiver accepts.
     */
    open val text: CharSequence? get() = textValue
    private var textValue: CharSequence? = null
    open var hint: CharSequence? = null
    /** The text size in pixels; the card view holder scales the label with it. */
    var textSize: Float = 0f
    /** The ellipsizing mode; the card view holder configures it per label display mode. */
    var ellipsize: TextUtils.TruncateAt? = null
    /** Whether the text scrolls horizontally (the marquee label mode). Set through the platform setter. */
    private var horizontallyScrolling: Boolean = false
    fun setHorizontallyScrolling(scrolling: Boolean) {
        horizontallyScrolling = scrolling
    }
    /** Whether the view is selected (the marquee label mode). */
    var isSelected: Boolean = false
    /** The maximum number of lines. */
    var maxLines: Int = 0
    /** The text colour. Set through the platform setter setTextColor(int). */
    private var textColorValue: Int = 0
    fun setTextColor(color: Int) {
        textColorValue = color
    }

    /** Whether the text is a single line. */
    var isSingleLine: Boolean = false

    /**
     * The installed input filters. The platform property is synthesized from the Java
     * `getFilters()`/`setFilters(...)` pair, so it is declared as a Kotlin property here; the
     * generated accessors keep the same Java-visible names.
     */
    var filters: Array<InputFilter> = emptyArray()

    /** The raw input type of the field. The platform only has a setter, setRawInputType(int). */
    private var rawInputTypeValue: Int = 0
    fun setRawInputType(type: Int) {
        rawInputTypeValue = type
    }

    /** The error text that was set. */
    var error: CharSequence? = null
        private set

    fun setError(error: CharSequence?) {
        this.error = error
    }

    private val textWatchers = ArrayList<TextWatcher>()
    private var editorActionListener: OnEditorActionListener? = null

    open fun setText(text: CharSequence?) {
        textValue = text
    }
    /** Last resource passed to the integer setter, without resolving Android resources. */
    var textResource: Int = 0
        private set
    fun setText(resource: Int) { textResource = resource }
    fun setHint(resource: Int) {
        hint = "res:$resource"
    }

    /** Compile-only text colors; the stand-in does not resolve Android theme attributes. */
    val currentTextColor: Int get() = textColorValue
    val currentHintTextColor: Int get() = 0

    /** The platform colours the text through a colour state list. */
    fun getTextColors(): ColorStateList = ColorStateList(textColorValue)

    /** The platform resolves a colour for the given view state. */
    class ColorStateList(private val defaultColor: Int) {
        fun getColorForState(state: IntArray, defaultColor: Int): Int = this.defaultColor
    }

    fun addTextChangedListener(watcher: TextWatcher) {
        textWatchers.add(watcher)
    }

    /** Deliver the callback explicitly in host tests; setText does not emulate platform events. */
    fun dispatchAfterTextChanged() {
        textWatchers.forEach { it.afterTextChanged(text as? Editable) }
    }

    /** Records the listener; the editor-action checks call the converted helper directly. */
    fun setOnEditorActionListener(listener: OnEditorActionListener?) {
        editorActionListener = listener
    }

    /** The platform interface is Java; a `fun interface` keeps lambda/SAM conversion working. */
    fun interface OnEditorActionListener {
        fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean
    }
}

/**
 * Stand-in for the masked-input surface used by the password helpers. The selection/transformation
 * members are `open` so the checks can drive a fake field; no text layout is modelled. The text
 * buffer backs both `text` and `editableText` like a real editable field.
 */
open class EditText : TextView {
    constructor() : super()
    constructor(context: Context) : super()

    private val buffer = SimpleEditable()

    open var transformationMethod: TransformationMethod? = null
    open val selectionEnd: Int = -1
    open var inputType: Int = 0

    /** The platform narrows `getText()` to `Editable`; the text is the editable buffer. */
    override val text: CharSequence? get() = buffer

    override fun setText(text: CharSequence?) {
        buffer.set(text?.toString() ?: "")
    }

    open val editableText: Editable get() = buffer

    open fun setSelection(index: Int) {}
}

/** Minimal recording adapter state; observers and platform list layout are not implemented. */
abstract class BaseAdapter {
    var changeNotifications: Int = 0
        private set
    open fun notifyDataSetChanged() { changeNotifications++ }
    abstract fun getCount(): Int
    abstract fun getItem(position: Int): Any?
    abstract fun getItemId(position: Int): Long
    abstract fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View
}

open class ArrayAdapter<T>(val context: Context, resource: Int, objects: MutableList<T>) : BaseAdapter() {
    private val items = objects
    fun clear() { items.clear(); notifyDataSetChanged() }
    fun addAll(values: Collection<T>) { items.addAll(values); notifyDataSetChanged() }
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): T = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    /** The position of the given item in the list. */
    fun getPosition(item: T?): Int = items.indexOf(item)

    /** The drop-down view resource that was set. */
    var dropDownViewResource = 0
        private set

    fun setDropDownViewResource(resId: Int) {
        dropDownViewResource = resId
    }

    /** Whether the item at the position is enabled. */
    open fun isEnabled(position: Int): Boolean = true

    /** The view shown in the drop-down list. */
    open fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
        android.widget.CheckedTextView()
    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = View()
}

class CheckedTextView : TextView() {
    var isChecked: Boolean = false
}

/** Base of the list widgets; the converted tag picker only needs the item-click listener slot. */
open class AdapterView<T> : android.view.ViewGroup() {
    /** The item click listener that was set. */
    var onItemClickListener: OnItemClickListener? = null

    /** The adapter the platform would lay out; the platform getter is `getAdapter()`. */
    var adapter: BaseAdapter? = null

    /** The platform reports the scrolled position; no list layout exists here. */
    fun getFirstVisiblePosition(): Int = 0

    /** Dispatches the item click to the recorded listener, like the platform would. */
    fun performItemClick(view: View?, position: Int, id: Long): Boolean {
        val listener = onItemClickListener ?: return false
        listener.onItemClick(this, view ?: View(), position, id)
        return true
    }

    fun interface OnItemClickListener {
        fun onItemClick(parent: AdapterView<*>?, view: View, position: Int, id: Long)
    }

    /** The platform declares the selection listener on the adapter view. It has two abstract methods,
     *  so it is not a SAM type and needs an object expression rather than a lambda. */
    interface OnItemSelectedListener {
        fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
        fun onNothingSelected(parent: AdapterView<*>?)
    }
}

class ListView(context: Context) : AdapterView<Any?>() {
    var divider: android.graphics.drawable.Drawable? = null

    /** The selected position of the list. */
    var selectedPosition: Int = 0
        private set

    /** The positions that are checked. */
    val checkedPositions: MutableSet<Int> = LinkedHashSet()

    fun setSelection(position: Int) {
        selectedPosition = position
    }

    fun setItemChecked(position: Int, checked: Boolean) {
        if (checked) checkedPositions.add(position) else checkedPositions.remove(position)
    }

    fun isItemChecked(position: Int): Boolean = checkedPositions.contains(position)
}

/** The shared base of the list and grid views. Its LayoutParams is the nested class the grid uses. */
open class AbsListView(context: Context) : AdapterView<Any?>() {
    class LayoutParams(width: Int, height: Int) : android.view.ViewGroup.LayoutParams(width, height)
}

open class GridView(context: Context) : AbsListView(context) {

    /** The column width of the grid. */
    var columnWidth: Int = 0

    /** The number of columns. */
    var numColumns: Int = 1

    /** The vertical spacing between the cells. */
    var verticalSpacing: Int = 0

    /** The horizontal spacing between the cells. */
    var horizontalSpacing: Int = 0

    /** The stretch mode of the grid. */
    var stretchMode: Int = STRETCH_COLUMN_WIDTH

    companion object {
        const val AUTO_FIT = -1
        const val STRETCH_COLUMN_WIDTH = 0
        const val STRETCH_COLUMN_HEIGHT = 1
        const val STRETCH_UNIFORM = 2
    }
}

open class ImageView(context: Context) : View() {
    fun setImageResource(id: Int) {}
    private var imageDrawable: android.graphics.drawable.Drawable? = null

    /**
     * The platform getters are Java methods, so the converted code calls `getDrawable()`;
     * the stand-in keeps the set drawable instead of decoding anything.
     */
    fun getDrawable(): android.graphics.drawable.Drawable? = imageDrawable

    fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        imageDrawable = drawable
    }

    fun setImageBitmap(bitmap: android.graphics.Bitmap?) {}

    /** How the image is scaled into its bounds. */
    enum class ScaleType {
        MATRIX, FIT_XY, FIT_START, FIT_CENTER, FIT_END, CENTER, CENTER_CROP, CENTER_INSIDE
    }

    /**
     * The platform only has `setAdjustViewBounds(boolean)` (there is no getter), so the stand-in
     * keeps the setter as a function and stores the state privately.
     */
    private var adjustViewBoundsFlag: Boolean = false

    /** The platform's `getScaleType()`/`setScaleType(ScaleType)` pair; see [adjustViewBoundsFlag]. */
    private var scaleTypeValue: ScaleType = ScaleType.FIT_CENTER

    /** The scale type of the image. */
    val scaleType: ScaleType
        get() = scaleTypeValue

    fun setAdjustViewBounds(adjustViewBounds: Boolean) {
        adjustViewBoundsFlag = adjustViewBounds
    }

    fun setScaleType(scaleType: ScaleType) {
        scaleTypeValue = scaleType
    }
}

/** Recording-only preference/dialog widgets; no focus, range enforcement or actual UI. */
open class FrameLayout(context: Context) : android.view.ViewGroup() {
    class LayoutParams(width: Int, height: Int) : android.view.ViewGroup.LayoutParams(width, height) {
        var gravity = 0
    }
}

/** Recording-only scroll container; real measuring, clipping and touch dispatch need a device. */
open class ScrollView(context: Context) : android.view.ViewGroup()

/** Inert linear layout; the converted about screens only look children up and add extras. */
open class LinearLayout(context: Context) : android.view.ViewGroup() {
    /** The orientation of the layout. */
    var orientation: Int = HORIZONTAL

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}

class NumberPicker(context: Context) : View() {
    var minValue = 0
    var maxValue = 0
    var wrapSelectorWheel = true
    var value = 0
    var focusCleared = false
        private set
    fun clearFocus() { focusCleared = true }
}

open class CompoundButton : TextView() {
    open var isChecked: Boolean = false
    fun interface OnCheckedChangeListener { fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) }
    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {}
}
class CheckBox : CompoundButton {
    constructor() : super()
    constructor(context: Context) : super()
}
class Button : TextView {
    constructor() : super()
    constructor(context: Context) : super()
    /** The compound drawables that were set, as (left, top, right, bottom) resource ids. */
    var compoundDrawables: IntArray = intArrayOf(0, 0, 0, 0)
        private set

    fun setCompoundDrawablesWithIntrinsicBounds(left: Int, top: Int, right: Int, bottom: Int) {
        compoundDrawables = intArrayOf(left, top, right, bottom)
    }

    fun setCompoundDrawablesWithIntrinsicBounds(left: android.graphics.drawable.Drawable?,
                                                top: android.graphics.drawable.Drawable?,
                                                right: android.graphics.drawable.Drawable?,
                                                bottom: android.graphics.drawable.Drawable?) {
        compoundDrawables = intArrayOf(0, 0, 0, 0)
    }

    fun setCompoundDrawablesRelativeWithIntrinsicBounds(start: Int, top: Int, end: Int, bottom: Int) {
        compoundDrawables = intArrayOf(start, top, end, bottom)
    }
}

/** The card progress bar; the holder animates its progress. */
open class ProgressBar(context: Context) : View() {
    var max: Int = 0
    var progress: Int = 0
}

/** An image button; the card view holder tints its drawable. */
open class ImageButton(context: Context) : ImageView(context)

/** The spinner used by the manual-entry dialog and the intro slides. */
open class Spinner(context: Context) : AdapterView<Any?>() {
    var selectedItemPosition: Int = 0
    var onItemSelectedListener: OnItemSelectedListener? = null

    fun setSelection(position: Int) {
        selectedItemPosition = position
    }

    fun getSelectedItem(): Any? = adapter?.getItem(selectedItemPosition)

    /** The device test inspects the platform popup drawable; no popup is rendered here. */
    fun getPopupBackground(): android.graphics.drawable.Drawable? = null
}
