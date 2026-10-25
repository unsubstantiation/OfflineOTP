package org.shadowice.flocke.andotp.Preferences

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.preference.DialogPreference
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Tasks.ChangeCredentialsTask
import org.shadowice.flocke.andotp.Tasks.TaskOutcome
import org.shadowice.flocke.andotp.Utilities.ConfirmedPasswordTransformationHelper
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.Constants.AuthMethod
import org.shadowice.flocke.andotp.Utilities.Constants.EncryptionType
import org.shadowice.flocke.andotp.Utilities.EditorActionHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.UIHelper

import java.util.Arrays
import java.util.Locale

import javax.crypto.SecretKey

/** Dialog preference that selects the authentication method and updates password/PIN credentials. */
open class CredentialsPreference(context: Context, attrs: AttributeSet?) :
    DialogPreference(context, attrs), AdapterView.OnItemClickListener, View.OnClickListener,
    TextWatcher, TextView.OnEditorActionListener {

    companion object {
        /** The auth method a fresh installation starts with. */
        @JvmField
        val DEFAULT_VALUE: AuthMethod = AuthMethod.NONE

        /**
         * Whether the typed credential may be saved for [method]. NONE/DEVICE store no credential
         * and are always saveable; a password/PIN must reach [minLength] and match its
         * confirmation. Mirrors the minimums the intro screen enforces.
         */
        @JvmStatic
        fun canSaveCredentials(
            method: AuthMethod,
            minLength: Int,
            password: CharSequence?,
            confirm: CharSequence?
        ): Boolean {
            if (method == AuthMethod.NONE || method == AuthMethod.DEVICE) return true
            val typed = password?.toString() ?: ""
            val confirmed = confirm?.toString() ?: ""
            return typed.isNotEmpty() && typed.length >= minLength && typed == confirmed
        }
    }

    private val entries: List<String>
    private val entryValues: List<AuthMethod> = Arrays.asList(
        AuthMethod.NONE,
        AuthMethod.PASSWORD,
        AuthMethod.PIN,
        AuthMethod.DEVICE
    )

    private var minLength = 0
    private var activeTask: ChangeCredentialsTask? = null

    private val settings: Settings = Settings(context)
    private var value: AuthMethod = AuthMethod.NONE
    private var oldEncryptionKey: SecretKey? = null

    private var credentialsLayout: LinearLayout? = null
    private var credentialsSelection: ListView? = null
    private var passwordLayout: TextInputLayout? = null
    private var passwordInput: TextInputEditText? = null
    private var passwordConfirm: EditText? = null
    private var tooShortWarning: TextView? = null

    private var btnCancel: Button? = null
    private var btnSave: Button? = null
    private var progressBar: ProgressBar? = null

    private var encryptionChangeCallback: EncryptionHelper.EncryptionChangeCallback? = null

    init {
        entries = context.resources.getStringArray(R.array.settings_entries_auth).toList()

        setDialogLayoutResource(R.layout.component_authentication)
    }

    /** The key the database is currently encrypted with (needed when changing the encryption). */
    fun setOldEncryptionKey(oldKey: SecretKey?) {
        oldEncryptionKey = oldKey
    }

    /** Called after the credentials (and possibly the encryption) were changed. */
    fun setEncryptionChangeCallback(changeCallback: EncryptionHelper.EncryptionChangeCallback?) {
        encryptionChangeCallback = changeCallback
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        builder.setPositiveButton(null as CharSequence?, null)
        builder.setNegativeButton(null as CharSequence?, null)
    }

    override fun onBindDialogView(view: View) {
        value = settings.getAuthMethod()

        val selection = requireNotNull(view.findViewById<ListView>(R.id.credentialSelection))
        credentialsSelection = selection

        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_single_choice,
            entries.toMutableList())
        selection.adapter = adapter

        val index = entryValues.indexOf(value)
        selection.setSelection(index)
        selection.setItemChecked(index, true)
        selection.onItemClickListener = this

        val layout = requireNotNull(view.findViewById<LinearLayout>(R.id.credentialsLayout))
        credentialsLayout = layout

        val pwdLayout = requireNotNull(view.findViewById<TextInputLayout>(R.id.passwordLayout))
        passwordLayout = pwdLayout
        val pwdInput = requireNotNull(view.findViewById<TextInputEditText>(R.id.passwordEdit))
        passwordInput = pwdInput
        val pwdConfirm = requireNotNull(view.findViewById<EditText>(R.id.passwordConfirm))
        passwordConfirm = pwdConfirm
        pwdInput.isSaveEnabled = false
        pwdConfirm.isSaveEnabled = false

        if (settings.getBlockAccessibility()) {
            pwdLayout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            pwdConfirm.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        }

        if (settings.getBlockAutofill()) {
            pwdLayout.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
            pwdConfirm.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO)
        }

        tooShortWarning = requireNotNull(view.findViewById<TextView>(R.id.tooShortWarning))

        pwdInput.addTextChangedListener(this)
        pwdConfirm.addTextChangedListener(this)
        pwdConfirm.setOnEditorActionListener(this)

        btnCancel = requireNotNull(view.findViewById<Button>(R.id.btnCancel))
        btnSave = requireNotNull(view.findViewById<Button>(R.id.btnSave))

        btnCancel?.setOnClickListener(this)
        btnSave?.setOnClickListener(this)

        progressBar = requireNotNull(view.findViewById<ProgressBar>(R.id.saveProgress))

        updateLayout()

        super.onBindDialogView(view)
    }

    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        if (restorePersistedValue) {
            val stringValue = getPersistedString(DEFAULT_VALUE.name.lowercase(Locale.ENGLISH))
            value = AuthMethod.valueOf(stringValue.uppercase(Locale.ENGLISH))
        } else {
            value = DEFAULT_VALUE
            persistString(value.name.lowercase(Locale.ENGLISH))
        }

        summary = entries[entryValues.indexOf(value)]
    }

    /** Validates the selection and starts the credential change. */
    private fun saveValues() {
        val encryptionType = settings.getEncryption()

        if (encryptionType == EncryptionType.PASSWORD) {
            if (value == AuthMethod.NONE || value == AuthMethod.DEVICE) {
                UIHelper.showGenericDialog(context, R.string.settings_dialog_title_error,
                    R.string.settings_dialog_msg_auth_invalid_with_encryption)
                return
            }
        }

        if (value == AuthMethod.DEVICE) {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?

            if (km == null || !km.isKeyguardSecure()) {
                Toast.makeText(context, R.string.settings_toast_auth_device_not_secure, Toast.LENGTH_LONG).show()
                return
            }
        }

        // The save button should only be accessible when a password has been entered (and confirmed)
        val input = passwordInput ?: return
        val task = ChangeCredentialsTask(context, encryptionType, oldEncryptionKey, value,
            input.text?.toString() ?: "")
        activeTask?.let {
            it.setOutcomeCallback(null)
            it.cancel()
        }
        activeTask = task
        task.setOutcomeCallback { outcome ->
            if (activeTask !== task) {
                task.disposeOutcome(outcome)
                return@setOutcomeCallback
            }
            activeTask = null
            if (outcome is TaskOutcome.Value)
                handleTaskResult(outcome.value)
            else {
                if (outcome is TaskOutcome.Failure)
                    Toast.makeText(context, R.string.settings_toast_encryption_change_failed, Toast.LENGTH_LONG).show()
                passwordInput?.setText("")
                passwordConfirm?.setText("")
                setInProgress(false)
                getDialog()?.dismiss()
            }
        }

        setInProgress(true)
        task.execute()
    }

    /** Disables the dialog while the change is running. */
    private fun setInProgress(inProgress: Boolean) {
        credentialsSelection?.isEnabled = !inProgress
        passwordInput?.isEnabled = !inProgress
        passwordConfirm?.isEnabled = !inProgress
        btnCancel?.isEnabled = !inProgress

        btnSave?.visibility = if (inProgress) View.GONE else View.VISIBLE
        progressBar?.visibility = if (inProgress) View.VISIBLE else View.GONE

        getDialog()?.setCancelable(!inProgress)
    }

    /** Persists the new value on success and closes the dialog. */
    private fun handleTaskResult(result: ChangeCredentialsTask.Result) {
        if (result.success) {
            // Credentials and auth mode were already committed with the ciphertext.
            summary = entries[entryValues.indexOf(value)]

            val newType = result.encryptionType
            val newKey = result.newEncryptionKey
            if (newType != null && newKey != null)
                encryptionChangeCallback?.onSuccessfulEncryptionChange(newType, newKey)
        } else {
            Toast.makeText(context, R.string.settings_toast_encryption_change_failed, Toast.LENGTH_LONG).show()
        }

        passwordInput?.setText("")
        passwordConfirm?.setText("")
        setInProgress(false)
        getDialog()?.dismiss()
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        activeTask?.let {
            it.setOutcomeCallback(null)
            it.cancel()
        }
        activeTask = null
        passwordInput?.setText("")
        passwordConfirm?.setText("")
        super.onDialogClosed(positiveResult)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.btnCancel -> getDialog()?.dismiss()
            R.id.btnSave -> saveValues()
            else -> Unit
        }
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        if (value == AuthMethod.NONE || value == AuthMethod.DEVICE) {
            // No credential is typed for these methods, so their save button stays usable.
            tooShortWarning?.visibility = View.GONE
            btnSave?.isEnabled = true
            return
        }

        val password = passwordInput?.editableText?.toString() ?: ""
        val confirm = passwordConfirm?.editableText?.toString() ?: ""

        // The warning alone used to leave Save enabled after the typed credential fell below the
        // minimum, so a too-short password/PIN could still be committed from this dialog.
        tooShortWarning?.visibility = if (password.length >= minLength) View.GONE else View.VISIBLE
        btnSave?.isEnabled = canSaveCredentials(value, minLength, password, confirm)
    }

    /** Shows or hides the password fields for the selected auth method. */
    private fun updateLayout() {
        if (value == AuthMethod.NONE || value == AuthMethod.DEVICE) {
            credentialsLayout?.visibility = View.GONE
            tooShortWarning?.visibility = View.GONE
            if (getDialog() != null) {
                UIHelper.hideKeyboard(context, passwordInput)
            }
            btnSave?.isEnabled = true
        } else if (value == AuthMethod.PASSWORD || value == AuthMethod.PIN) {
            prepareAuthMethodInputFields()
        }
    }

    /** Configures the input fields for password or PIN entry. */
    private fun prepareAuthMethodInputFields() {
        if (value != AuthMethod.PIN && value != AuthMethod.PASSWORD) {
            return
        }
        val isPassword = value == AuthMethod.PASSWORD

        credentialsLayout?.visibility = View.VISIBLE
        val layoutHintRes = if (isPassword) R.string.settings_hint_password else R.string.settings_hint_pin
        passwordLayout?.hint = context.getString(layoutHintRes)
        val confirmHintRes =
            if (isPassword) R.string.settings_hint_password_confirm else R.string.settings_hint_pin_confirm
        passwordConfirm?.hint = context.getString(confirmHintRes)

        val inputType = if (isPassword)
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        else
            (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        passwordInput?.inputType = inputType
        passwordConfirm?.inputType = inputType
        val layout = passwordLayout
        val input = passwordInput
        val confirm = passwordConfirm
        if (layout != null && input != null && confirm != null) {
            ConfirmedPasswordTransformationHelper.setup(layout, input, confirm)
        }

        minLength = if (isPassword) Constants.AUTH_MIN_PASSWORD_LENGTH else Constants.AUTH_MIN_PIN_LENGTH
        val shortWarningRes = if (isPassword) R.string.settings_label_short_password else R.string.settings_label_short_pin
        tooShortWarning?.setText(context.getString(shortWarningRes, minLength))

        passwordInput?.requestFocus()
        UIHelper.showKeyboard(context, passwordInput)
        btnSave?.isEnabled = false
    }

    /** Applies the selected auth method and clears the password fields. */
    override fun onItemClick(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
        value = entryValues[position]
        updateLayout()
        clearInputFields()
    }

    /** Clears both password fields. */
    private fun clearInputFields() {
        passwordInput?.setText(null)
        passwordConfirm?.setText(null)
    }

    override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
        if (EditorActionHelper.isActionDoneOrKeyboardEnter(actionId, event)) {
            if (btnSave?.isEnabled == true) btnSave?.performClick()
            return true
        } else {
            // Ignore action up after keyboard enter. Otherwise the cancel button would be selected
            // after pressing enter with an invalid password.
            return EditorActionHelper.isActionUpKeyboardEnter(event)
        }
    }

    // Unused TextWatcher callbacks (required by the interface)
    override fun afterTextChanged(s: Editable?) {}

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
}
