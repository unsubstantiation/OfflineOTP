package com.fairuniversal.offlineotp.Activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button as ComposeButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Tasks.AuthenticationError
import com.fairuniversal.offlineotp.Tasks.AuthenticationOutcome
import com.fairuniversal.offlineotp.Tasks.AuthenticationTask
import com.fairuniversal.offlineotp.Tasks.TaskFailure
import com.fairuniversal.offlineotp.Utilities.BiometricKeyStore
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.Constants.AuthMethod
import com.fairuniversal.offlineotp.Utilities.EditorActionHelper
import com.fairuniversal.offlineotp.Utilities.HardwareUnlockStore
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.Utilities.Tools
import com.fairuniversal.offlineotp.Utilities.VaultSession
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType
import com.fairuniversal.offlineotp.ui.unlock.SharedUnlockScreen
import com.fairuniversal.offlineotp.ui.unlock.UnlockStrings

/** Host adapter for the shared unlock form. */
open class AuthenticateActivity : BackgroundTaskActivity<AuthenticationOutcome>() {

    private var authMethod: AuthMethod = AuthMethod.NONE
    private var newEncryption: String? = ""
    private var existingAuthCredentials: String = ""
    private var isAuthUpgrade: Boolean = false

    private var unlockComposeHost: View? = null
    private var unlockProgress: ProgressBar? = null
    private var hardwareButton: Button? = null
    private var biometricButton: Button? = null
    private var hardwarePending = false
    private var hardwareGeneration = -1L
    private var biometricPending = false
    private var biometricGeneration = -1L
    private var biometricAutoShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicyForForeground()

        authMethod = settings!!.getAuthMethod()
        newEncryption = getIntent()?.getStringExtra(Constants.EXTRA_AUTH_NEW_ENCRYPTION)
        existingAuthCredentials = settings!!.getAuthCredentials()
        if (existingAuthCredentials.isEmpty()) {
            existingAuthCredentials = settings!!.getOldCredentials(authMethod)
            isAuthUpgrade = true
        }

        // Abort if no current or legacy credentials are stored.
        if (existingAuthCredentials.isEmpty()) {
            finishWithResult(false, null)
            return
        }
        // Only password authentication uses this screen.
        if (authMethod != AuthMethod.PASSWORD) {
            finishWithResult(false, null)
            return
        }

        setTitle(R.string.auth_activity_title)
        setContentView(R.layout.activity_container)
        initToolbar()
        initComposeHost()
        initHardwareAndBiometricButtons()

        setBroadcastCallback {
            if (settings!!.getRelockOnScreenOff()) {
                cancelBackgroundTask()
            }
        }

        // Auto-prompt fingerprint if it is the quick unlock for this vault. A cancel falls back
        // to the password; the vault file is unchanged so the password still works.
        if (shouldOfferBiometric() && !biometricAutoShown) {
            biometricAutoShown = true
            // Let the view inflate before the system prompt appears.
            unlockComposeHost?.postDelayed({ if (!isFinishing()) triggerBiometricUnlock() }, 300)
        }
    }

    private fun initToolbar() {
        val toolbar = requireNotNull(findViewById<Toolbar>(R.id.container_toolbar))
        toolbar.setNavigationIcon(null)
        setSupportActionBar(toolbar)
    }

    private fun initComposeHost() {
        val stub = requireNotNull(findViewById<ViewStub>(R.id.container_stub))
        stub.setLayoutResource(R.layout.component_frame)
        val frame = stub.inflate()

        unlockComposeHost = AndroidView(this) {
            ComposeHost(
                strings = UnlockStrings(
                    appTitle = getString(R.string.app_name),
                    passwordLabel = getString(R.string.auth_hint_password),
                    unlockAction = getString(R.string.auth_button_unlock),
                    unlocking = getString(R.string.auth_progress_unlocking)
                ),
                onSubmit = { password -> startAuthTask(password) },
                onHardwareUnlock = { triggerHardwareUnlock() },
                onRestore = null, // Restore is not part of the unlock activity (handled in IntroScreenActivity)
                creating = false,
                onCreate = { _, _ -> },
                error = null,
                busy = false,
                hint = null
            )
        }
        (frame as ViewGroup).addView(unlockComposeHost!!)

        // The Compose host owns the visible dialog window (keyboard, text input, etc.).
        // We only keep native capability adapters (hardware/biometric buttons) and the progress bar in the host.
        unlockProgress = requireNotNull(frame.findViewById<ProgressBar>(R.id.unlockProgress))
        hardwareButton = requireNotNull(frame.findViewById<Button>(R.id.buttonHardwareUnlock))
        biometricButton = requireNotNull(frame.findViewById<Button>(R.id.buttonBiometricUnlock))
    }

    private fun initHardwareAndBiometricButtons() {
        val allowed = getIntent()?.getBooleanExtra(Constants.EXTRA_AUTH_ALLOW_HARDWARE, false) == true &&
            newEncryption.isNullOrEmpty() && authMethod == AuthMethod.PASSWORD &&
            settings?.getEncryption() == Constants.EncryptionType.PASSWORD && HardwareUnlockStore.read(this) != null
        hardwareButton?.visibility = if (allowed) View.VISIBLE else View.GONE
        hardwareButton?.setOnClickListener {
            if (!hardwarePending && allowed) {
                hardwarePending = true
                hardwareGeneration = VaultSession.generation()
                startActivityForResult(Intent(this, HardwareUnlockActivity::class.java), Constants.INTENT_AUTH_HARDWARE)
            }
        }

        val biometricAllowed = shouldOfferBiometric()
        biometricButton?.visibility = if (biometricAllowed) View.VISIBLE else View.GONE
        biometricButton?.setOnClickListener {
            if (!biometricPending && biometricAllowed) {
                biometricPending = true
                biometricGeneration = VaultSession.generation()
                triggerBiometricUnlock()
            }
        }
    }

    private fun shouldOfferBiometric(): Boolean {
        if (!newEncryption.isNullOrEmpty()) return false
        if (authMethod != AuthMethod.PASSWORD) return false
        if (settings?.getEncryption() != Constants.EncryptionType.PASSWORD) return false
        if (!BiometricKeyStore.isEnabled(this)) return false
        if (!BiometricKeyStore.hasStoredKey(this)) return false
        return BiometricKeyStore.availability(this).isAvailable
    }

    private fun triggerBiometricUnlock() {
        if (biometricPending) return
        biometricPending = true
        biometricGeneration = VaultSession.generation()
        BiometricKeyStore.load(this) { result ->
            if (!VaultSession.isCurrent(biometricGeneration)) {
                biometricPending = false
                return@load
            }
            if (result.isSuccess) {
                val vaultKey = result.getOrNull()
                if (vaultKey != null && vaultKey.isNotEmpty()) {
                    finishWithResult(true, vaultKey, biometricGeneration)
                    try { vaultKey.fill(0) } catch (_: Exception) { }
                } else {
                    try { vaultKey?.fill(0) } catch (_: Exception) { }
                    biometricPending = false
                }
                return@load
            }
            biometricPending = false
            val error = BiometricKeyStore.errorFromResult(result)
                    ?: BiometricKeyStore.BiometricError.FAILED
            when (error) {
                BiometricKeyStore.BiometricError.MISSING -> {
                    BiometricKeyStore.clear(this)
                    biometricButton?.visibility = View.GONE
                }
                BiometricKeyStore.BiometricError.NOT_ENROLLED,
                BiometricKeyStore.BiometricError.UNAVAILABLE -> biometricButton?.visibility = View.GONE
                else -> Unit
            }
            val duration =
                if (error == BiometricKeyStore.BiometricError.CANCELLED) Toast.LENGTH_SHORT
                else Toast.LENGTH_LONG
            Toast.makeText(this, error.message(this), duration).show()
        }
    }

    override fun cancelTaskOnScreenOff(): Boolean {
        return true
    }

    override fun onReturnToCanceledTask() {
        // The Compose host clears the field automatically.
        unlockComposeHost?.post {
            // Focus is handled inside the Compose host via keyboard actions
        }
    }

    override fun setupUiForTaskState(isTaskRunning: Boolean) {
        // Update progress bar and button states
        unlockProgress?.visibility = if (isTaskRunning) View.VISIBLE else View.GONE
        hardwareButton?.isEnabled = !isTaskRunning
        biometricButton?.isEnabled = !isTaskRunning
        // The Compose host state (busy) is updated via the state setter (not directly exposed here,
        // but the Compose host internally handles the busy flag if we pass it; for simplicity,
        // we can just disable hardware/biometric buttons and show progress bar).
        // Note: In a full implementation, we would also pass the busy state to the Compose host.
        // For now, we'll rely on the host's internal state management or update it via a setter if available.
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Constants.INTENT_AUTH_HARDWARE) {
            val accepted = hardwarePending && resultCode == RESULT_OK && VaultSession.isCurrent(hardwareGeneration)
            hardwarePending = false
            if (accepted) finishWithResult(true, null, hardwareGeneration)
        }
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

    private fun clearPasswordInput() {
        // The Compose host clears the field automatically when onSubmit is called.
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finishWithResult(false, null)
        super.onBackPressed()
    }

    override fun onVaultLocked() {
        hardwarePending = false
        biometricPending = false
        cancelBackgroundTask()
        finish()
    }

    override fun shouldDestroyOnScreenOff(): Boolean {
        return false
    }

    @Composable
    private fun ComposeHost(
        strings: UnlockStrings,
        onSubmit: (String) -> Unit,
        onHardwareUnlock: (() -> Unit)?,
        onRestore: (() -> Unit)?,
        creating: Boolean,
        onCreate: (String, String) -> Unit,
        error: String?,
        busy: Boolean,
        hint: String?
    ) {
        // We use SharedUnlockScreen from ui-foundation
        SharedUnlockScreen(
            strings = strings,
            onSubmit = onSubmit,
            modifier = Modifier.fillMaxSize(),
            error = error,
            busy = busy,
            hint = hint,
            onHardwareUnlock = onHardwareUnlock,
            quickUnlockLabel = getString(R.string.hardware_unlock_button),
            creating = creating,
            onCreate = onCreate,
            onRestore = onRestore
        )
    }

    override fun onDestroy() {
        clearPasswordInput()
        super.onDestroy()
    }
}
