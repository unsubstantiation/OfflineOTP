package org.shadowice.flocke.andotp.Activities

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.SparseArray
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView

import androidx.appcompat.widget.SwitchCompat
import androidx.viewpager.widget.ViewPager

import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.heinrichreimersoftware.materialintro.app.IntroActivity
import com.heinrichreimersoftware.materialintro.app.SlideFragment
import com.heinrichreimersoftware.materialintro.slide.FragmentSlide
import com.heinrichreimersoftware.materialintro.slide.SimpleSlide

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Tasks.FinishIntroTask
import org.shadowice.flocke.andotp.Tasks.TaskOutcome
import org.shadowice.flocke.andotp.Utilities.ConfirmedPasswordTransformationHelper
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.EditorActionHelper
import org.shadowice.flocke.andotp.Utilities.UIHelper
import org.shadowice.flocke.andotp.Utilities.VaultSession

import java.util.Arrays
import java.util.Locale

/** First-run wizard for configuring encryption, authentication, and Android backup settings. */
open class IntroScreenActivity : IntroActivity() {
    private var encryptionFragment: EncryptionFragment? = null
    private var authenticationFragment: AuthenticationFragment? = null
    private var androidSyncFragment: AndroidSyncFragment? = null
    private var introFinishedFragment: IntroFinishedFragment? = null

    private var setupFinished = false
    private var encryptionKey: ByteArray? = null
    private var setupGeneration: Long = VaultSession.generation()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val encryption = EncryptionFragment()
        val authentication = AuthenticationFragment()
        val sync = AndroidSyncFragment(encryption)
        val finished = IntroFinishedFragment(this, IntroFinishedFragment.SaveSettingsCallback { success, key ->
            this.setupFinished = success
            this.encryptionKey?.let { Arrays.fill(it, 0) }
            this.encryptionKey = key?.copyOf()
        })

        encryptionFragment = encryption
        authenticationFragment = authentication
        androidSyncFragment = sync
        introFinishedFragment = finished

        encryption.setEncryptionChangedCallback(EncryptionFragment.EncryptionChangedCallback { newEncryptionType ->
            authentication.updateEncryptionType(newEncryptionType)
        })

        setButtonBackFunction(BUTTON_BACK_FUNCTION_BACK)

        addSlide(SimpleSlide.Builder()
            .title(R.string.intro_slide1_title)
            .description(R.string.intro_slide1_desc)
            .background(R.color.colorPrimary)
            .backgroundDark(R.color.colorPrimaryDark)
            .canGoBackward(false)
            .scrollable(false)
            .build()
        )

        addSlide(FragmentSlide.Builder()
            .background(R.color.colorPrimary)
            .backgroundDark(R.color.colorPrimaryDark)
            .fragment(encryption)
            .build()
        )

        authentication.setSlidePos(getSlides().size)

        addSlide(FragmentSlide.Builder()
            .background(R.color.colorPrimary)
            .backgroundDark(R.color.colorPrimaryDark)
            .fragment(authentication)
            .build()
        )

        addSlide(FragmentSlide.Builder()
            .background(R.color.colorPrimary)
            .backgroundDark(R.color.colorPrimaryDark)
            .fragment(sync)
            .build()
        )

        addSlide(FragmentSlide.Builder()
            .background(R.color.colorPrimary)
            .backgroundDark(R.color.colorPrimaryDark)
            .fragment(finished)
            .build()
        )

        addOnNavigationBlockedListener { position, _ ->
            if (position == 2)
                authentication.flashWarning()
        }

        addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageSelected(position: Int) {
                // Guard against saving a second time when the finish button is pressed on the final slide.
                if (position == getCount() - 1 && currentSlidePosition < getCount()) {
                    try {
                        finished.saveSettings(encryption.getEncryptionType(), authentication.getAuthMethod(),
                            authentication.getPassword(), sync.getSyncEnabled())
                    } finally {
                        authentication.clearCredentialInputs()
                    }
                }
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageScrollStateChanged(state: Int) {}
        })
    }

    override fun onDestroy() {
        authenticationFragment?.clearCredentialInputs()
        encryptionKey?.let { Arrays.fill(it, 0) }
        encryptionKey = null
        super.onDestroy()
    }

    /** Returns the setup result to MainActivity and hands any derived key to [VaultSession]. */
    override fun onSendActivityResult(result: Int): Intent {
        val data = Intent()
        data.putExtra(Constants.EXTRA_INTRO_FINISHED, setupFinished)
        val key = encryptionKey
        if (key != null) {
            VaultSession.offerDerivedKey(key, setupGeneration)
            Arrays.fill(key, 0)
            encryptionKey = null
        }

        return data
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Prevent leaving the intro screen before initial setup completes.
    }

    /** Slide to choose between KeyStore and password encryption. */
    open class EncryptionFragment : SlideFragment() {
        private var encryptionChangedCallback: EncryptionChangedCallback? = null

        private var selection: Spinner? = null
        private var desc: TextView? = null

        private var selectionMapping: SparseArray<Constants.EncryptionType> = SparseArray()

        fun setEncryptionChangedCallback(cb: EncryptionChangedCallback?) {
            encryptionChangedCallback = cb
        }

        private fun generateSelectionMapping() {
            val encValues = getResources().getStringArray(R.array.settings_values_encryption)

            selectionMapping = SparseArray()
            for (i in encValues.indices)
                selectionMapping.put(i, Constants.EncryptionType.valueOf(encValues[i].uppercase(Locale.getDefault())))
        }

        fun getEncryptionType(): Constants.EncryptionType {
            val spinner = selection ?: return Constants.EncryptionType.KEYSTORE
            return selectionMapping.get(spinner.selectedItemPosition) ?: Constants.EncryptionType.KEYSTORE
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                                  savedInstanceState: Bundle?): View {
            val root = inflater.inflate(R.layout.component_intro_encryption, container, false)

            val spinner = requireNotNull(root.findViewById<Spinner>(R.id.introEncryptionSelection))
            selection = spinner
            val description = requireNotNull(root.findViewById<TextView>(R.id.introEncryptionDesc))
            desc = description

            generateSelectionMapping()

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, selectedView: View?, i: Int, selectedId: Long) {
                    val encryptionType = selectionMapping.get(i)

                    if (encryptionType == Constants.EncryptionType.PASSWORD)
                        description.setText(R.string.intro_slide2_desc_password)
                    else if (encryptionType == Constants.EncryptionType.KEYSTORE)
                        description.setText(R.string.intro_slide2_desc_keystore)

                    if (encryptionType != null)
                        encryptionChangedCallback?.onEncryptionChanged(encryptionType)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            spinner.setSelection(selectionMapping.indexOfValue(Constants.EncryptionType.PASSWORD))

            return root
        }

        /** Notifies the other slides when the encryption choice changes. */
        fun interface EncryptionChangedCallback {
            fun onEncryptionChanged(newEncryptionType: Constants.EncryptionType)
        }
    }

    /** Last slide: saves the collected settings and shows whether the setup succeeded. */
    open class IntroFinishedFragment(private val context: Context, private val callback: SaveSettingsCallback) :
        SlideFragment() {
        private var title: TextView? = null
        private var message: TextView? = null
        private var progress: ProgressBar? = null

        private var canGoForward = false
        private var activeTask: FinishIntroTask? = null

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                                  savedInstanceState: Bundle?): View {
            val root = inflater.inflate(R.layout.component_intro_finished, container, false)

            title = requireNotNull(root.findViewById<TextView>(R.id.introFinishedTitle))
            message = requireNotNull(root.findViewById<TextView>(R.id.introFinishedMessage))
            progress = requireNotNull(root.findViewById<ProgressBar>(R.id.introFinishedProgress))

            return root
        }

        internal fun saveSettings(encryptionType: Constants.EncryptionType, authMethod: Constants.AuthMethod,
                                 password: String?, androidSyncEnabled: Boolean) {
            if ((authMethod == Constants.AuthMethod.PASSWORD || authMethod == Constants.AuthMethod.PIN) &&
                    (password == null || password.isEmpty())) {
                title?.setText(R.string.intro_slide4_title_failed)
                message?.setText(R.string.intro_slide4_desc_failed)

                canGoForward = false

                callback.onAfterSaveSettings(false, null)
                return
            }

            val task = FinishIntroTask(context, encryptionType, authMethod, password, androidSyncEnabled)
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
                if (outcome is TaskOutcome.Value) {
                    val result = outcome.value
                    try {
                        handleTaskResult(result)
                    } finally {
                        task.disposeOutcome(outcome)
                    }
                } else {
                    title?.setText(R.string.intro_slide4_title_failed)
                    message?.setText(R.string.intro_slide4_desc_failed)
                    progress?.visibility = View.INVISIBLE
                    canGoForward = false
                    callback.onAfterSaveSettings(false, null)
                }
            }

            progress?.visibility = View.VISIBLE
            task.execute()
        }

        override fun onDestroyView() {
            activeTask?.let {
                it.setOutcomeCallback(null)
                it.cancel()
            }
            activeTask = null
            super.onDestroyView()
        }

        private fun handleTaskResult(result: FinishIntroTask.Result) {
            if (!VaultSession.isCurrent(result.generation)) {
                result.encryptionKey?.let { Arrays.fill(it, 0) }
                return
            }
            if (result.saveSuccessful) {
                title?.setText(R.string.intro_slide4_title)
                message?.setText(R.string.intro_slide4_desc)

                canGoForward = true
            } else {
                title?.setText(R.string.intro_slide4_title_failed)
                message?.setText(R.string.intro_slide4_desc_failed)

                canGoForward = false
            }

            progress?.visibility = View.INVISIBLE

            callback.onAfterSaveSettings(result.saveSuccessful, result.encryptionKey)
        }

        override fun canGoForward(): Boolean {
            return canGoForward
        }

        /** Reports the outcome of the initial setup (and the new encryption key). */
        fun interface SaveSettingsCallback {
            fun onAfterSaveSettings(success: Boolean, encryptionKey: ByteArray?)
        }
    }

    /** Slide to enable the Android backup service (only useful for password encryption). */
    open class AndroidSyncFragment(private val encryptionFragment: EncryptionFragment) : SlideFragment() {
        private var introAndroidSync: SwitchCompat? = null

        fun getSyncEnabled(): Boolean {
            return introAndroidSync?.isChecked == true
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                                  savedInstanceState: Bundle?): View {
            val root = inflater.inflate(R.layout.component_intro_android_sync, container, false)

            val toggle = requireNotNull(root.findViewById<SwitchCompat>(R.id.introAndroidSync))
            introAndroidSync = toggle
            toggle.setOnCheckedChangeListener { compoundButton, b ->
                compoundButton.setText(if (b) R.string.settings_toast_android_sync_enabled
                else R.string.settings_toast_android_sync_disabled)
            }

            toggle.isChecked = false
            toggle.isEnabled = false
            toggle.setText(R.string.security_platform_backup_disabled)

            return root
        }
    }

    /** Slide to choose the auth method and enter the password/PIN. */
    open class AuthenticationFragment : SlideFragment(), TextView.OnEditorActionListener {
        private var encryptionType: Constants.EncryptionType = Constants.EncryptionType.KEYSTORE

        private var slidePos = -1

        private var minLength = Constants.AUTH_MIN_PASSWORD_LENGTH
        private var lengthWarning = ""
        private var noPasswordWarning = ""
        private var confirmPasswordWarning = ""
        private var passwordMismatchWarning = ""

        private var desc: TextView? = null
        private var selection: Spinner? = null
        private var authWarnings: TextView? = null
        private var credentialsLayout: LinearLayout? = null
        private var passwordLayout: TextInputLayout? = null
        private var passwordInput: TextInputEditText? = null
        private var passwordConfirm: EditText? = null

        private var selectionMapping: SparseArray<Constants.AuthMethod> = SparseArray()

        fun setSlidePos(pos: Int) {
            slidePos = pos
        }

        /** Adapts the slide to the encryption choice (password encryption requires password/PIN). */
        fun updateEncryptionType(encryptionType: Constants.EncryptionType) {
            this.encryptionType = encryptionType

            val description = desc
            val spinner = selection
            if (description != null && spinner != null) {
                if (encryptionType == Constants.EncryptionType.KEYSTORE) {
                    description.setText(R.string.intro_slide3_desc_keystore)

                    spinner.setSelection(selectionMapping.indexOfValue(Constants.AuthMethod.NONE))
                } else if (encryptionType == Constants.EncryptionType.PASSWORD) {
                    description.setText(R.string.intro_slide3_desc_password)

                    val selectedMethod = selectionMapping.get(spinner.selectedItemPosition)
                    if (selectedMethod != Constants.AuthMethod.PASSWORD && selectedMethod != Constants.AuthMethod.PIN)
                        spinner.setSelection(selectionMapping.indexOfValue(Constants.AuthMethod.PASSWORD))
                }
            }
        }

        private fun generateSelectionMapping() {
            val authValues = Constants.AuthMethod.values()

            selectionMapping = SparseArray()
            for (i in authValues.indices)
                selectionMapping.put(i, authValues[i])
        }

        @Suppress("SameParameterValue")
        private fun updateWarning(resId: Int) {
            updateWarning(getString(resId))
        }

        private fun updateWarning(warning: String) {
            authWarnings?.setText(warning)
        }

        private fun hideWarning() {
            authWarnings?.visibility = View.GONE
            authWarnings?.setText(null)
        }

        /** Briefly highlights the warning text when the user tries to leave the slide. */
        fun flashWarning() {
            val warnings = authWarnings ?: return
            if (warnings.text?.toString()?.isEmpty() != false) {
                warnings.visibility = View.GONE
            } else {
                warnings.visibility = View.VISIBLE
                val animator = ObjectAnimator.ofInt(warnings, "backgroundColor",
                    Color.TRANSPARENT, getResources().getColor(R.color.colorAccent), Color.TRANSPARENT)
                animator.setDuration(500)
                animator.setRepeatCount(0)
                animator.setInterpolator(AccelerateDecelerateInterpolator())
                animator.setEvaluator(ArgbEvaluator())
                animator.start()
            }
        }

        fun getAuthMethod(): Constants.AuthMethod {
            val spinner = selection ?: return Constants.AuthMethod.NONE
            return selectionMapping.get(spinner.selectedItemPosition) ?: Constants.AuthMethod.NONE
        }

        fun getPassword(): String? {
            return passwordInput?.text?.toString()
        }

        /** Clears credential text as soon as setup takes ownership or the fragment view is discarded. */
        fun clearCredentialInputs() {
            passwordInput?.setText("")
            passwordConfirm?.setText("")
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                                  savedInstanceState: Bundle?): View {
            val root = inflater.inflate(R.layout.component_intro_authentication, container, false)

            desc = requireNotNull(root.findViewById<TextView>(R.id.introAuthDesc))
            val spinner = requireNotNull(root.findViewById<Spinner>(R.id.introAuthSelection))
            selection = spinner
            authWarnings = requireNotNull(root.findViewById<TextView>(R.id.introAuthWarnings))
            val credentials = requireNotNull(root.findViewById<LinearLayout>(R.id.introCredentialsLayout))
            credentialsLayout = credentials
            val pwdLayout = requireNotNull(root.findViewById<TextInputLayout>(R.id.introPasswordLayout))
            passwordLayout = pwdLayout
            val pwdInput = requireNotNull(root.findViewById<TextInputEditText>(R.id.introPasswordEdit))
            passwordInput = pwdInput
            pwdInput.isSaveEnabled = false
            val pwdConfirm = requireNotNull(root.findViewById<EditText>(R.id.introPasswordConfirm))
            passwordConfirm = pwdConfirm
            pwdConfirm.isSaveEnabled = false

            generateSelectionMapping()

            val authEntries = getResources().getStringArray(R.array.settings_entries_auth)
            val spinnerArrayAdapter = object : ArrayAdapter<String>(getIntroActivity()!!,
                android.R.layout.simple_spinner_item, authEntries.toMutableList()) {
                override fun isEnabled(position: Int): Boolean {
                    return encryptionType != Constants.EncryptionType.PASSWORD ||
                            position == selectionMapping.indexOfValue(Constants.AuthMethod.PASSWORD) ||
                            position == selectionMapping.indexOfValue(Constants.AuthMethod.PIN)
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getDropDownView(position, convertView, parent)
                    val tv = view as TextView

                    tv.isEnabled = encryptionType != Constants.EncryptionType.PASSWORD ||
                            position == selectionMapping.indexOfValue(Constants.AuthMethod.PASSWORD) ||
                            position == selectionMapping.indexOfValue(Constants.AuthMethod.PIN)

                    return view
                }
            }

            spinnerArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = spinnerArrayAdapter

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(adapterView: AdapterView<*>?, selectedView: View?, i: Int, selectedId: Long) {
                    val authMethod = selectionMapping.get(i)

                    if (authMethod == Constants.AuthMethod.PASSWORD) {
                        setupForPasswordInput()
                    } else if (authMethod == Constants.AuthMethod.PIN) {
                        setupForPinInput()
                    } else {
                        credentials.visibility = View.INVISIBLE
                        UIHelper.hideKeyboard(getIntroActivity()!!, root)
                    }

                    pwdInput.setText(null)
                    pwdConfirm.setText(null)

                    authWarnings?.visibility = View.GONE

                    updateNavigation()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            val textWatcher = object : TextWatcher {
                override fun onTextChanged(charSequence: CharSequence?, i: Int, i1: Int, i2: Int) {
                    updateNavigation()
                }

                override fun beforeTextChanged(charSequence: CharSequence?, i: Int, i1: Int, i2: Int) {}

                override fun afterTextChanged(editable: Editable?) {}
            }

            pwdInput.addTextChangedListener(textWatcher)
            pwdConfirm.addTextChangedListener(textWatcher)

            pwdConfirm.setOnEditorActionListener(this)

            spinner.setSelection(selectionMapping.indexOfValue(Constants.AuthMethod.PASSWORD))

            return root
        }

        private fun setupForPasswordInput() {
            credentialsLayout?.visibility = View.VISIBLE

            passwordLayout?.hint = getString(R.string.settings_hint_password)
            passwordConfirm?.hint = getString(R.string.settings_hint_password_confirm)

            passwordInput?.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            passwordConfirm?.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

            val pwdLayout = passwordLayout
            val pwdInput = passwordInput
            val pwdConfirm = passwordConfirm
            if (pwdLayout != null && pwdInput != null && pwdConfirm != null) {
                ConfirmedPasswordTransformationHelper.setup(pwdLayout, pwdInput, pwdConfirm)
            }

            minLength = Constants.AUTH_MIN_PASSWORD_LENGTH
            lengthWarning = getString(R.string.settings_label_short_password, minLength)
            noPasswordWarning = getString(R.string.intro_slide3_warn_no_password)
            confirmPasswordWarning = getString(R.string.intro_slide3_warn_confirm_password)
            passwordMismatchWarning = getString(R.string.intro_slide3_warn_password_mismatch)

            focusOnPasswordInput()
        }

        private fun focusOnPasswordInput() {
            if (getIntroActivity()?.currentSlidePosition == slidePos) {
                passwordInput?.requestFocus()
                UIHelper.showKeyboard(getContext()!!, passwordInput)
            }
        }

        private fun setupForPinInput() {
            credentialsLayout?.visibility = View.VISIBLE

            passwordLayout?.hint = getString(R.string.settings_hint_pin)
            passwordConfirm?.hint = getString(R.string.settings_hint_pin_confirm)

            passwordInput?.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            passwordConfirm?.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

            val pwdLayout = passwordLayout
            val pwdInput = passwordInput
            val pwdConfirm = passwordConfirm
            if (pwdLayout != null && pwdInput != null && pwdConfirm != null) {
                ConfirmedPasswordTransformationHelper.setup(pwdLayout, pwdInput, pwdConfirm)
            }

            minLength = Constants.AUTH_MIN_PIN_LENGTH
            lengthWarning = getString(R.string.settings_label_short_pin, minLength)
            noPasswordWarning = getString(R.string.intro_slide3_warn_no_pin)
            confirmPasswordWarning = getString(R.string.intro_slide3_warn_confirm_pin)
            passwordMismatchWarning = getString(R.string.intro_slide3_warn_pin_mismatch)

            focusOnPasswordInput()
        }

        override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
            if (EditorActionHelper.isActionDoneOrKeyboardEnter(actionId, event)) {
                nextSlide()
                return true
            } else {
                // Ignore action up after keyboard enter. Otherwise the go-back button would be selected
                // after pressing enter with an invalid password.
                return EditorActionHelper.isActionUpKeyboardEnter(event)
            }
        }

        override fun canGoForward(): Boolean {
            val authMethod = selectionMapping.get(selection?.selectedItemPosition ?: -1)

            if (authMethod == Constants.AuthMethod.PIN || authMethod == Constants.AuthMethod.PASSWORD) {
                val password = passwordInput?.text?.toString()

                val confirm = passwordConfirm?.text?.toString() ?: ""

                if (password != null && password.isNotEmpty()) {
                    if (password.length < minLength) {
                        updateWarning(lengthWarning)
                        return false
                    } else {
                        if (confirm.isNotEmpty()) {
                            if (confirm == password) {
                                hideWarning()
                                return true
                            } else {
                                updateWarning(passwordMismatchWarning)
                                return false
                            }
                        } else {
                            updateWarning(confirmPasswordWarning)
                            return false
                        }
                    }
                } else {
                    updateWarning(noPasswordWarning)
                    return false
                }
            } else if (authMethod == Constants.AuthMethod.DEVICE) {
                val context = getContext()
                if (context == null)
                    return false

                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?

                if (km == null || !km.isKeyguardSecure()) {
                    updateWarning(R.string.settings_toast_auth_device_not_secure)
                    return false
                }

                hideWarning()
                return true
            } else {
                hideWarning()
                return true
            }
        }
    }
}
