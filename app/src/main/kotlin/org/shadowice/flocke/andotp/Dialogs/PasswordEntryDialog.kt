package org.shadowice.flocke.andotp.Dialogs

import android.content.Context
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.ConfirmedPasswordTransformationHelper
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.EditorActionHelper
import org.shadowice.flocke.andotp.Utilities.Tools

/** Dialog that prompts for a backup password (single entry or with confirmation). */
open class PasswordEntryDialog @JvmOverloads constructor(
    context: Context,
    newMode: Mode,
    blockAccessibility: Boolean,
    blockAutofill: Boolean,
    newCallback: PasswordEnteredCallback?,
    newCancelCallback: PasswordCanceledCallback? = null
) : AppCompatDialog(context, Tools.getThemeResource(context, R.attr.dialogTheme)),
    View.OnClickListener, TextWatcher, TextView.OnEditorActionListener {

    /** ENTER for a single input (restore), UPDATE for input plus confirmation (new password). */
    enum class Mode { ENTER, UPDATE }

    /** Receives the password entered by the user. */
    fun interface PasswordEnteredCallback {
        fun onPasswordEntered(newPassword: String)
    }

    /** Notifies the owner when a protected operation is explicitly canceled by the user. */
    fun interface PasswordCanceledCallback {
        fun onPasswordCanceled()
    }

    private val dialogMode: Mode = newMode
    private val callback: PasswordEnteredCallback? = newCallback
    private val cancelCallback: PasswordCanceledCallback? = newCancelCallback
    private var passwordAccepted = false

    private val passwordInput: TextInputEditText
    private val passwordConfirm: EditText
    private val okButton: Button
    private val tooShortWarning: TextView

    init {
        setTitle(R.string.dialog_title_enter_password)
        setOnCancelListener {
            if (!passwordAccepted) cancelCallback?.onPasswordCanceled()
        }
        setContentView(R.layout.dialog_password_entry)
        // Resize with the IME so the buttons stay visible above the keyboard (#805).
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val passwordLayout = requireNotNull(findViewById<TextInputLayout>(R.id.passwordInputLayout))
        passwordInput = requireNotNull(findViewById(R.id.passwordInput))
        passwordConfirm = requireNotNull(findViewById(R.id.passwordConfirm))
        passwordInput.isSaveEnabled = false
        passwordConfirm.isSaveEnabled = false
        tooShortWarning = requireNotNull(findViewById(R.id.tooShortWarning))
        tooShortWarning.setText(
            context.getString(R.string.settings_label_short_password, Constants.AUTH_MIN_PASSWORD_LENGTH)
        )
        ConfirmedPasswordTransformationHelper.setup(passwordLayout, passwordInput, passwordConfirm)

        if (blockAccessibility) {
            passwordLayout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            passwordConfirm.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        }

        if (blockAutofill) {
            passwordLayout.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
            passwordConfirm.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO)
        }

        okButton = requireNotNull(findViewById(R.id.buttonOk))
        okButton.setOnClickListener(this)
        okButton.isEnabled = false

        val cancelButton = requireNotNull(findViewById<Button>(R.id.buttonCancel))
        cancelButton.setOnClickListener(this)

        if (dialogMode == Mode.UPDATE) {
            passwordConfirm.visibility = View.VISIBLE

            passwordInput.addTextChangedListener(this)
            passwordConfirm.addTextChangedListener(this)

            passwordConfirm.setOnEditorActionListener(this)
        } else if (dialogMode == Mode.ENTER) {
            passwordConfirm.visibility = View.GONE

            passwordInput.addTextChangedListener(this)

            passwordInput.setOnEditorActionListener(this)
        }
    }

    // TextWatcher callbacks
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        if (passwordInput.editableText.length >= Constants.AUTH_MIN_PASSWORD_LENGTH) {
            tooShortWarning.visibility = View.GONE

            okButton.isEnabled = dialogMode == Mode.ENTER ||
                TextUtils.equals(passwordInput.editableText, passwordConfirm.editableText)
        } else {
            tooShortWarning.visibility = View.VISIBLE
            okButton.isEnabled = false
        }
    }

    override fun afterTextChanged(s: Editable?) {}
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
        if (EditorActionHelper.isActionDoneOrKeyboardEnter(actionId, event)) {
            if (okButton.isEnabled) okButton.performClick()
            return true
        } else if (EditorActionHelper.isActionUpKeyboardEnter(event)) {
            // Ignore action up after keyboard enter. Otherwise the cancel button would be selected
            // after pressing enter with an invalid password.
            return true
        }

        return false
    }

    private fun clearSensitiveInputs() {
        passwordInput.setText("")
        passwordConfirm.setText("")
    }

    override fun dismiss() {
        clearSensitiveInputs()
        super.dismiss()
    }

    // View.OnClickListener callback
    override fun onClick(view: View) {
        try {
            if (view.id == R.id.buttonOk) {
                passwordAccepted = true
                callback?.onPasswordEntered(passwordInput.text.toString())
            } else {
                cancelCallback?.onPasswordCanceled()
            }
        } finally {
            dismiss()
        }
    }
}
