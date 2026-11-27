package com.fairuniversal.offlineotp.Activities

import android.os.Bundle
import android.view.View
import com.yubico.yubikit.core.application.CommandState
import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.DatabaseHelper
import com.fairuniversal.offlineotp.Utilities.HardwareFidoClient
import com.fairuniversal.offlineotp.Utilities.HardwareKeyDiscovery
import com.fairuniversal.offlineotp.Utilities.HardwareUnlockEnvelope
import com.fairuniversal.offlineotp.Utilities.HardwareUnlockStore
import com.fairuniversal.offlineotp.Utilities.SessionState
import com.fairuniversal.offlineotp.Utilities.VaultReadResult
import com.fairuniversal.offlineotp.Utilities.VaultSession
import com.fairuniversal.offlineotp.ui.host.HardwareUnlockComposeView
import com.fairuniversal.offlineotp.ui.host.BusyProgressComposeView
import com.fairuniversal.offlineotp.ui.host.ComposeDialogHost
import javax.crypto.spec.SecretKeySpec

/** One-use FIDO2 unlock; key material is handed to the session, never an Intent. */
class HardwareUnlockActivity : BaseActivity() {
    private var generation = -1L
    private lateinit var discovery: HardwareKeyDiscovery
    private var progress: ComposeDialogHost? = null
    private var unlockForm: HardwareUnlockComposeView? = null
    private var foreground = false
    private var unlockError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        generation = VaultSession.generation()
        if (!sessionReady() || settings?.getAuthMethod() != Constants.AuthMethod.PASSWORD ||
            settings?.getEncryption() != Constants.EncryptionType.PASSWORD || HardwareUnlockStore.read(this) == null) {
            finish(); return
        }
        discovery = HardwareKeyDiscovery(this) { sessionReady() }
        setTitle(R.string.hardware_title)
        setContentView(R.layout.activity_hardware_unlock)

    }

    /** A fresh form per attempt: no PIN survives submit, stop, cancellation or recreation. */
    private fun showUnlockForm() {
        if (!foreground || !sessionReady() || isFinishing() || progress != null || unlockForm != null) return
        var host: HardwareUnlockComposeView? = null
        host = HardwareUnlockComposeView(
            activity = this,
            transportNotice = getString(R.string.hardware_unlock_notice) + "\n\n" + getString(R.string.hardware_connect),
            pinLabel = getString(R.string.hardware_fido_pin_hint),
            confirmLabel = getString(R.string.hardware_unlock_button),
            error = unlockError,
            onSubmit = { typedPin ->
                if (unlockForm === host && foreground && sessionReady() && !isFinishing()) {
                    closeUnlockForm()
                    unlock(typedPin.copyOf())
                } else if (unlockForm === host) closeUnlockForm()
            },
            onDismiss = {
                if (unlockForm === host) {
                    foreground = false
                    closeUnlockForm()
                    finish()
                }
            }
        )
        if (settings?.getBlockAccessibility() == true)
            host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
        unlockForm = host
        host.show()
    }

    private fun closeUnlockForm() {
        val old = unlockForm
        unlockForm = null
        old?.close()
    }

    private fun cancelUnlockAttempt(expected: ComposeDialogHost) {
        // A callback from an old progress window must not cancel a newer attempt.
        if (progress === expected) expected.dismiss()
    }

    private fun sessionReady(): Boolean = VaultSession.isCurrent(generation) && VaultSession.snapshot() is SessionState.Unlocking

    private fun unlock(chars: CharArray) {
        if (!foreground || !sessionReady() || progress != null || isFinishing() ||
            chars.size > com.fairuniversal.offlineotp.Utilities.InputPolicy.MAX_TEXT_CHARS) {
            chars.fill('\u0000'); return
        }
        val envelope = HardwareUnlockStore.read(this)
        val request = envelope?.let(HardwareUnlockEnvelope::request)
        if (envelope == null || request == null) {
            chars.fill('\u0000')
            unlockError = getString(R.string.hardware_failed)
            showUnlockForm()
            return
        }
        closeUnlockForm()
        unlockError = null
        val supplied = chars.takeIf { it.isNotEmpty() }
        lateinit var prompt: BusyProgressComposeView
        prompt = BusyProgressComposeView(
            activity = this,
            title = getString(R.string.hardware_title),
            message = getString(R.string.hardware_connect),
            cancelLabel = getString(android.R.string.cancel),
            onCancel = { cancelUnlockAttempt(prompt) }
        )
        progress?.dismiss()
        progress = prompt
        prompt.setOnDismissListener {
            if (progress === prompt) {
                progress = null
                discovery.cancel()
                showUnlockForm()
            }
        }
        prompt.show()
        val state = CommandState()
        discovery.start(HardwareKeyDiscovery.Protocol.FIDO, { device, permitted ->
            if (!permitted()) throw IllegalStateException("Canceled")
            val prf = HardwareFidoClient.calculate(device, request, supplied, state)
            try {
                val raw = HardwareUnlockEnvelope.open(envelope, prf) ?: throw IllegalStateException("Invalid hardware key")
                try {
                    if (!permitted()) throw IllegalStateException("Canceled")
                    val loaded = DatabaseHelper.readVault(this, SecretKeySpec(raw, "AES"))
                    if (loaded !is VaultReadResult.Loaded) throw IllegalStateException("Vault did not open")
                    loaded.close()
                    raw
                } catch (error: Exception) {
                    raw.fill(0)
                    throw error
                }
            } finally { prf.fill(0) }
        }, { it.fill(0) }, { chars.fill('\u0000') }, { state.cancel() }) { raw, success ->
            val ownsAttempt = progress === prompt
            if (ownsAttempt) progress = null
            prompt.dismiss()
            var accepted = false
            try {
                if (ownsAttempt && foreground && success && raw != null) synchronized(VaultSession.MutationLock) {
                    val current = HardwareUnlockStore.read(this)
                    if (sessionReady() && current != null && current.contentEquals(envelope))
                        accepted = VaultSession.offerDerivedKey(raw, generation)
                }
            } finally { raw?.fill(0) }
            if (accepted) { setResult(RESULT_OK); finish() }
            else if (ownsAttempt && foreground && sessionReady()) {
                unlockError = getString(R.string.hardware_failed)
                showUnlockForm()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (::discovery.isInitialized) discovery.resume()
        if (::discovery.isInitialized) showUnlockForm()
    }
    override fun onPause() {
        if (::discovery.isInitialized) discovery.pause()
        super.onPause()
    }
    override fun onStop() {
        foreground = false
        cleanup()
        super.onStop()
    }
    override fun onVaultLocked() {
        foreground = false
        cleanup()
        super.onVaultLocked()
    }
    override fun onDestroy() {
        foreground = false
        cleanup()
        if (::discovery.isInitialized) discovery.close()
        super.onDestroy()
    }
    private fun cleanup() {
        closeUnlockForm()
        if (::discovery.isInitialized) discovery.cancel()
        val old = progress
        progress = null
        old?.dismiss()
    }
}
