package com.fairuniversal.offlineotp.View.AutoFillable

import android.content.Context
import android.util.AttributeSet
import android.view.autofill.AutofillValue
import com.google.android.material.textfield.TextInputEditText

/** Notifies a listener after the platform has inserted text supplied by an autofill service. */
open class AutoFillableTextInputEditText : TextInputEditText {
    private var listener: AutoFillTextListener? = null

    // Keep the three distinct super-constructor calls (including Material's default styling).
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun autofill(value: AutofillValue?) {
        super.autofill(value)
        val callback = listener ?: return
        if (value != null && value.isText) {
            callback.onTextAutoFilled(requireNotNull(value.textValue))
        }
    }

    open fun setAutoFillTextListener(listener: AutoFillTextListener?) {
        this.listener = listener
    }

    fun interface AutoFillTextListener {
        fun onTextAutoFilled(text: CharSequence)
    }
}
