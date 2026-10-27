package org.shadowice.flocke.andotp.Activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.KeyEvent
import android.view.View
import android.view.ViewStub
import android.view.WindowManager.LayoutParams
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.widget.Toolbar

import com.google.android.material.textfield.TextInputLayout

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Tasks.AuthenticationError
import org.shadowice.flocke.andotp.Tasks.AuthenticationOutcome
import org.shadowice.flocke.andotp.Tasks.AuthenticationTask
import org.shadowice.flocke.andotp.Tasks.TaskFailure
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.Constants.AuthMethod
import org.shadowice.flocke.andotp.Utilities.EditorActionHelper
import org.shadowice.flocke.andotp.Utilities.HardwareUnlockStore
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.View.AutoFillable.AutoFillableTextInputEditText

import java.util.Arrays

/** Prompts for the password or PIN and hands the derived key to [VaultSession]. */
open class AuthenticateActivity : BackgroundTaskActivity<AuthenticationOutcome>(),
    TextView.OnEditorActionListener, View.OnClickListener {

    private val autoFillTextListener = AutoFillableTextInputEditText.AutoFillTextListener { text ->
        startAuthTask(text.toString())
    }

    private var authMethod: AuthMethod = AuthMethod.NONE
    private var newEncryption: String? = ""
    private var existingAuthCredentials: String = ""
    private var isAuthUpgrade: Boolean = false

    private var passwordLayout: TextInputLayout? = null
    private var passwordInput: AutoFillableTextInputEditText? = null
    private var unlockButton: Button? = null
    private var hardwareButton: Button? = null
    private var hardwarePending = false
    private var hardwareGeneration = -1L
    private var unlockProgress: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!settings!!.getScreenshotsEnabled())
            getWindow().setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE)

        authMethod = settings!!.getAuthMethod()
        newEncryption = getIntent()?.getStringExtra(Constants.EXTRA_AUTH_NEW_ENCRYPTION)
        existingAuthCredentials = settings!!.getAuthCredentials()
        if (existingAuthCredentials.isEmpty()) {
            existingAuthCredentials = settings!!.getOldCredentials(authMethod)
            isAuthUpgrade = true
        }

        // Abort if no current or legacy credentials are stored.
        if (existingAuthCredentials.isEmpty()) {
            val missingPwResId = if (authMethod == AuthMethod.PASSWORD)
                R.string.auth_toast_password_missing else R.string.auth_toast_pin_missing
            Toast.makeText(this, missingPwResId, Toast.LENGTH_LONG).show()
            finishWithResult(false, null)
            return
        }
        // Only password and PIN authentication use this screen.
        if (authMethod != AuthMethod.PASSWORD && authMethod != AuthMethod.PIN) {
            finishWithResult(false, null)
            return
        }

        setTitle(R.string.auth_activity_title)
        setContentView(R.layout.activity_container)
        initToolbar()
        initPasswordViews()

        setBroadcastCallback {
            if (settings!!.getRelockOnScreenOff()) {
                cancelBackgroundTask()
            }
        }

        getWindow().setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun initToolbar() {
        val toolbar = requireNotNull(findViewById<Toolbar>(R.id.container_toolbar))
        toolbar.setNavigationIcon(null)
        setSupportActionBar(toolbar)
    }

    private fun initPasswordViews() {
        val stub = requireNotNull(findViewById<ViewStub>(R.id.container_stub))
        stub.setLayoutResource(R.layout.content_authenticate)
        val v = stub.inflate()

        initPasswordLabelView(v)
        initPasswordLayoutView(v)
        initPasswordInputView(v)
        initUnlockViews(v)
    }

    private fun initPasswordLabelView(v: View) {
        val labelMsg = getIntent()?.getIntExtra(Constants.EXTRA_AUTH_MESSAGE, R.string.auth_msg_authenticate)
            ?: R.string.auth_msg_authenticate
        val passwordLabel = requireNotNull(v.findViewById<TextView>(R.id.passwordLabel))
        passwordLabel.setText(labelMsg)
    }

    private fun initPasswordLayoutView(v: View) {
        val layout = requireNotNull(v.findViewById<TextInputLayout>(R.id.passwordLayout))
        passwordLayout = layout
        val hintResId = if (authMethod == AuthMethod.PASSWORD) R.string.auth_hint_password else R.string.auth_hint_pin
        layout.hint = getString(hintResId)
        if (settings!!.getBlockAccessibility()) {
            layout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
        }
        if (settings!!.getBlockAutofill()) {
            layout.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
        }
    }

    private fun initPasswordInputView(v: View) {
        val input = requireNotNull(v.findViewById<AutoFillableTextInputEditText>(R.id.passwordEdit))
        passwordInput = input
        input.isSaveEnabled = false
        val inputType = if (authMethod == AuthMethod.PASSWORD)
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        else
            (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        input.inputType = inputType
        input.transformationMethod = PasswordTransformationMethod.getInstance()
        input.setOnEditorActionListener(this)
    }

    private fun initUnlockViews(v: View) {
        val button = requireNotNull(v.findViewById<Button>(R.id.buttonUnlock))
        unlockButton = button
        button.setOnClickListener(this)
        unlockProgress = requireNotNull(v.findViewById<ProgressBar>(R.id.unlockProgress))
        val hardware = requireNotNull(v.findViewById<Button>(R.id.buttonHardwareUnlock))
        hardwareButton = hardware
        val allowed = getIntent()?.getBooleanExtra(Constants.EXTRA_AUTH_ALLOW_HARDWARE, false) == true &&
            newEncryption.isNullOrEmpty() && authMethod == AuthMethod.PASSWORD &&
            settings?.getEncryption() == Constants.EncryptionType.PASSWORD && HardwareUnlockStore.read(this) != null
        hardware.visibility = if (allowed) View.VISIBLE else View.GONE
        hardware.setOnClickListener {
            if (!hardwarePending && allowed) {
                clearPasswordInput()
                hardwarePending = true
                hardwareGeneration = VaultSession.generation()
                startActivityForResult(Intent(this, HardwareUnlockActivity::class.java), Constants.INTENT_AUTH_HARDWARE)
            }
        }
    }

    override fun cancelTaskOnScreenOff(): Boolean {
        return true
    }

    override fun onReturnToCanceledTask() {
        val input = passwordInput ?: return
        clearPasswordInput()
        input.requestFocus()
        val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.showSoftInput(input, 0)
    }

    private fun clearPasswordInput() {
        passwordInput?.setText("")
    }

    override fun setupUiForTaskState(isTaskRunning: Boolean) {
        val layout = passwordLayout ?: return
        layout.isEnabled = !isTaskRunning
        passwordInput?.isEnabled = !isTaskRunning
        unlockButton?.isEnabled = !isTaskRunning
        hardwareButton?.isEnabled = !isTaskRunning
        unlockButton?.visibility = if (isTaskRunning) View.INVISIBLE else View.VISIBLE
        unlockProgress?.visibility = if (isTaskRunning) View.VISIBLE else View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Constants.INTENT_AUTH_HARDWARE) {
            val accepted = hardwarePending && resultCode == RESULT_OK && VaultSession.isCurrent(hardwareGeneration)
            hardwarePending = false
            if (accepted) finishWithResult(true, null, hardwareGeneration)
        }
    }

    override fun onClick(view: View) {
        val text = passwordInput?.text
        startAuthTask(text?.toString() ?: "")
    }

    override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
        if (EditorActionHelper.isActionDoneOrKeyboardEnter(actionId, event)) {
            startAuthTask(v?.text?.toString() ?: "")
            return true
        }
        return false
    }

    private fun startAuthTask(plainPassword: String) {
        val task = AuthenticationTask(this, isAuthUpgrade, existingAuthCredentials, plainPassword)
        try {
            startBackgroundTask(task)
        } finally {
            // The task owns its immutable copy; do not leave another copy in the UI widget.
            clearPasswordInput()
        }
    }

    override fun onTaskFailure(error: TaskFailure) {
        finishWithResult(false, null)
    }

    override fun onTaskResult(result: AuthenticationOutcome) {
        if (result is AuthenticationOutcome.Rejected) {
            if (result.error == AuthenticationError.LEGACY_MIGRATION_FAILED)
                Toast.makeText(this, R.string.settings_toast_auth_upgrade_failed, Toast.LENGTH_LONG).show()
            finishWithResult(false, null)
            return
        }
        val verified = result as AuthenticationOutcome.Verified
        try {
            if (!VaultSession.isCurrent(verified.generation)) finishWithResult(false, null)
            else finishWithResult(true, verified.key.takeBytes(), verified.generation)
        } finally {
            verified.close()
        }
    }

    private fun finishWithResult(success: Boolean, encryptionKey: ByteArray?) {
        finishWithResult(success, encryptionKey, VaultSession.generation())
    }

    private fun finishWithResult(success: Boolean, encryptionKey: ByteArray?, expected: Long) {
        clearPasswordInput()
        val data = Intent()
        val requested = newEncryption
        if (requested != null && requested.isNotEmpty())
            data.putExtra(Constants.EXTRA_AUTH_NEW_ENCRYPTION, requested)
        var ok = success
        if (encryptionKey != null) {
            ok = ok && VaultSession.offerDerivedKey(encryptionKey, expected)
            Arrays.fill(encryptionKey, 0)
        }
        if (ok)
            setResult(RESULT_OK, data)
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finishWithResult(false, null)
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        val input = passwordInput
        if (input != null && settings!!.getAutoUnlockAfterAutofill()) {
            input.setAutoFillTextListener(autoFillTextListener)
        }
    }

    override fun onStop() {
        passwordInput?.setAutoFillTextListener(null)
        super.onStop()
    }

    override fun onDestroy() {
        clearPasswordInput()
        passwordInput?.setAutoFillTextListener(null)
        super.onDestroy()
    }

    override fun onVaultLocked() {
        hardwarePending = false
        cancelBackgroundTask()
        clearPasswordInput()
        finish()
    }

    override fun shouldDestroyOnScreenOff(): Boolean {
        return false
    }
}
