package com.fairuniversal.offlineotp.Preferences

import android.content.Context
import android.content.res.TypedArray
import android.preference.DialogPreference
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.NumberPicker
import com.fairuniversal.offlineotp.R

/** Framework preference that displays a number picker as a dialog. */
open class NumberPickerPreference : DialogPreference {
    private val minValue: Int
    private val maxValue: Int
    private val wrapSelectorWheel: Boolean
    private lateinit var picker: NumberPicker
    private var currentValue = 0

    constructor(context: Context, attrs: AttributeSet?) :
        this(context, attrs, android.R.attr.dialogPreferenceStyle)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        val attributes = context.obtainStyledAttributes(attrs, R.styleable.NumberPickerPreference)
        minValue = attributes.getInteger(R.styleable.NumberPickerPreference_minValue, DEFAULT_MIN_VALUE)
        maxValue = attributes.getInteger(R.styleable.NumberPickerPreference_maxValue, DEFAULT_MAX_VALUE)
        wrapSelectorWheel = attributes.getBoolean(
            R.styleable.NumberPickerPreference_wrapSelectorWheel, DEFAULT_WRAP_SELECTOR_WHEEL
        )
        attributes.recycle()
    }

    protected override fun onCreateDialogView(): View {
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.gravity = Gravity.CENTER
        picker = NumberPicker(context).apply { layoutParams = params }
        return FrameLayout(context).apply { addView(picker) }
    }

    protected override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        picker.minValue = minValue
        picker.maxValue = maxValue
        picker.wrapSelectorWheel = wrapSelectorWheel
        picker.value = getValue()
    }

    protected override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            // Commit any text being edited before asking listeners whether the change is allowed.
            picker.clearFocus()
            val newValue = picker.value
            if (callChangeListener(newValue)) setValue(newValue)
        }
    }

    protected override fun onGetDefaultValue(a: TypedArray, index: Int): Any = a.getInt(index, minValue)

    protected override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        val initialValue = if (restorePersistedValue) getPersistedInt(minValue) else defaultValue as Int
        setValue(initialValue)
        summary = initialValue.toString()
    }

    /** Stores and displays the current value, including direct programmatic updates. */
    open fun setValue(value: Int) {
        currentValue = value
        persistInt(currentValue)
        summary = currentValue.toString()
    }

    open fun getValue(): Int = currentValue

    companion object {
        const val DEFAULT_MAX_VALUE = 100
        const val DEFAULT_MIN_VALUE = 0
        const val DEFAULT_WRAP_SELECTOR_WHEEL = true
    }
}
