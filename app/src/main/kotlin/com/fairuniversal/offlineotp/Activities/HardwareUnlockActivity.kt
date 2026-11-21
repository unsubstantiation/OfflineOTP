package com.fairuniversal.offlineotp.Activities

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
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
import com.fairuniversal.offlineotp.ui.host.ComposeDialogHost
import com.fairuniversal.offlineotp.ui.host.EntryDetailsComposeView
import javax.crypto.spec.SecretKeySpec

/** One-use FIDO2 unlock; key material is handed to the session, never an Intent. */
class HardwareUnlockActivity : BaseActivity() {
    private var generation = -1L
    private lateinit var discovery: HardwareKeyDiscovery
    private lateinit var pin: EditText
    private var progress: ComposeDialogHost? = null

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
        pin = requireNotNull(findViewById(R.id.hardwareUnlockPin))
        pin.isSaveEnabled = false
        if (settings?.getBlockAccessibility() == true)
            pin.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
        requireNotNull(findViewById<Button>(R.id.hardwareUnlockStart)).setOnClickListener { unlock() }
    }

    private fun sessionReady(): Boolean = VaultSession.isCurrent(generation) && VaultSession.snapshot() is SessionState.Unlocking

    private fun unlock() {
        if (!sessionReady()) return
        val envelope = HardwareUnlockStore.read(this) ?: return
        val request = HardwareUnlockEnvelope.request(envelope) ?: return
        val text = pin.text
        val chars = CharArray(text?.length ?: 0) { text!![it] }
        pin.setText("")
        val supplied = chars.takeIf { it.isNotEmpty() }
        var promptRef: ComposeDialogHost? = null
        val prompt = EntryDetailsComposeView(
            activity = this,
            title = getString(R.string.hardware_title),
            body = getString(R.string.hardware_connect),
            confirmLabel = getString(android.R.string.cancel),
            onDismiss = {
                val active = promptRef
                if (active != null && progress === active) {
                    progress = null
                    discovery.cancel()
                }
                active?.dismiss()
            }
        )
        promptRef = prompt
        progress?.dismiss()
        progress = prompt
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
            if (progress === prompt) progress = null
            prompt.dismiss()
            var accepted = false
            try {
                if (success && raw != null) synchronized(VaultSession.MutationLock) {
                    val current = HardwareUnlockStore.read(this)
                    if (sessionReady() && current != null && current.contentEquals(envelope))
                        accepted = VaultSession.offerDerivedKey(raw, generation)
                }
            } finally { raw?.fill(0) }
            if (accepted) { setResult(RESULT_OK); finish() }
            else if (sessionReady()) Toast.makeText(this, R.string.hardware_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::discovery.isInitialized) discovery.resume()
    }
    override fun onPause() {
        if (::discovery.isInitialized) discovery.pause()
        super.onPause()
    }
    override fun onStop() {
        cleanup()
        super.onStop()
    }
    override fun onVaultLocked() {
        cleanup()
        super.onVaultLocked()
    }
    override fun onDestroy() {
        cleanup()
        if (::discovery.isInitialized) discovery.close()
        super.onDestroy()
    }
    private fun cleanup() {
        if (::pin.isInitialized) pin.setText("")
        if (::discovery.isInitialized) discovery.cancel()
        progress?.dismiss()
        progress = null
    }
}
