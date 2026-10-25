package org.shadowice.flocke.andotp.Preferences

import android.app.AlertDialog
import android.content.Context
import android.content.res.TypedArray
import android.preference.DialogPreference
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.ConfirmedPasswordTransformationHelper
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import org.shadowice.flocke.andotp.Utilities.Settings
import java.nio.charset.StandardCharsets
import java.security.KeyPair

/** Dialog preference that stores a password or PIN encrypted with the KeyStore RSA key pair. */
open class PasswordEncryptedPreference(context: Context, attrs: AttributeSet?) :
    DialogPreference(context, attrs), View.OnClickListener, TextWatcher {

    /** Which kind of credential the dialog asks for. */
    enum class Mode { PASSWORD, PIN }

    private fun loadOrGenerateKey(): KeyPair? = try {
        KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_PASSWORD)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun loadExistingKey(): KeyPair? = try {
        KeyStoreHelper.loadAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_PASSWORD)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private var mode: Mode = Mode.PASSWORD

    private lateinit var passwordInput: TextInputEditText
    private lateinit var passwordConfirm: EditText
    private lateinit var btnSave: Button

    private var encryptedValue: String? = null

    init {
        setDialogLayoutResource(R.layout.component_password)
    }

    /** Sets whether a password or a PIN is entered. */
    open fun setMode(mode: Mode) {
        this.mode = mode
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        builder.setPositiveButton(null as CharSequence?, null)
        builder.setNegativeButton(null as CharSequence?, null)
    }

    override fun onBindDialogView(view: View) {
        val settings = Settings(context)

        val passwordLayout = requireNotNull(view.findViewById<TextInputLayout>(R.id.passwordLayout))
        passwordInput = requireNotNull(view.findViewById(R.id.passwordEdit))
        passwordConfirm = requireNotNull(view.findViewById(R.id.passwordConfirm))
        passwordInput.isSaveEnabled = false
        passwordConfirm.isSaveEnabled = false

        if (settings.getBlockAccessibility()) {
            passwordLayout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            passwordConfirm.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        }

        if (settings.getBlockAutofill()) {
            passwordLayout.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
            passwordConfirm.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO)
        }

        val btnCancel = requireNotNull(view.findViewById<Button>(R.id.btnCancel))
        btnSave = requireNotNull(view.findViewById(R.id.btnSave))
        btnSave.isEnabled = false

        btnCancel.setOnClickListener(this)
        btnSave.setOnClickListener(this)

        passwordInput.setText(restoreAndDecrypt(encryptedValue))

        if (mode == Mode.PASSWORD) {
            passwordLayout.hint = context.getString(R.string.settings_hint_password)
            passwordConfirm.setHint(R.string.settings_hint_password_confirm)

            passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            passwordConfirm.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else if (mode == Mode.PIN) {
            passwordLayout.hint = context.getString(R.string.settings_hint_pin)
            passwordConfirm.setHint(R.string.settings_hint_pin_confirm)

            passwordInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            passwordConfirm.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        ConfirmedPasswordTransformationHelper.setup(passwordLayout, passwordInput, passwordConfirm)

        passwordConfirm.addTextChangedListener(this)
        passwordInput.addTextChangedListener(this)

        super.onBindDialogView(view)
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? = a.getString(index)

    /** Encrypts a plaintext value immediately; plaintext is never retained in a preference field. */
    private fun encryptAndPersist(value: String) {
        if (value.isEmpty()) {
            if (persistString(DEFAULT_VALUE)) encryptedValue = DEFAULT_VALUE
            return
        }
        try {
            val pair = requireNotNull(loadOrGenerateKey())
            val encBytes = EncryptionHelper.encrypt(pair.getPublic(), value.toByteArray(StandardCharsets.UTF_8))
            val encoded = Base64.encodeToString(encBytes, Base64.URL_SAFE)
            if (persistString(encoded)) encryptedValue = encoded
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, R.string.settings_toast_encryption_no_key, Toast.LENGTH_LONG).show()
        }
    }

    /** Decrypts only while binding the dialog, returning an empty value if no credential is stored. */
    private fun restoreAndDecrypt(encValue: String?): String {
        if (encValue.isNullOrEmpty()) return DEFAULT_VALUE

        return try {
            val pair = loadExistingKey() ?: return DEFAULT_VALUE
            val encBytes = Base64.decode(encValue, Base64.URL_SAFE)
            String(EncryptionHelper.decrypt(pair.getPrivate(), encBytes), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            DEFAULT_VALUE
        }
    }

    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        if (restorePersistedValue) {
            encryptedValue = getPersistedString(DEFAULT_VALUE)
        } else {
            // This preference has no android:defaultValue. The framework therefore supplies null
            // the first time it is attached; keep the empty value without persisting it.
            val initialValue = defaultValue as? String ?: run {
                encryptedValue = null
                return
            }
            encryptAndPersist(initialValue)
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.btnCancel -> requireNotNull(getDialog()).dismiss()
            R.id.btnSave -> {
                encryptAndPersist(passwordInput.text.toString())
                requireNotNull(getDialog()).dismiss()
            }
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (this::passwordInput.isInitialized) passwordInput.setText("")
        if (this::passwordConfirm.isInitialized) passwordConfirm.setText("")
        super.onDialogClosed(positiveResult)
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        if (passwordConfirm.editableText.toString() == passwordInput.editableText.toString()) {
            btnSave.isEnabled = true
        } else {
            btnSave.isEnabled = false
        }
    }

    // Unused TextWatcher callbacks (required by the interface)
    override fun afterTextChanged(s: Editable?) {}
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    companion object {
        private const val DEFAULT_VALUE = ""
    }
}
