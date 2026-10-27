package org.shadowice.flocke.andotp.Activities

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.yubico.yubikit.core.application.CommandState
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.HardwareFidoClient
import org.shadowice.flocke.andotp.Utilities.HardwareKeyDiscovery
import org.shadowice.flocke.andotp.Utilities.HardwareUnlockEnvelope
import org.shadowice.flocke.andotp.Utilities.HardwareUnlockStore
import org.shadowice.flocke.andotp.Utilities.SessionState
import org.shadowice.flocke.andotp.Utilities.VaultReadResult
import org.shadowice.flocke.andotp.Utilities.VaultSession
import javax.crypto.spec.SecretKeySpec

/** One-use FIDO2 unlock; key material is handed to the session, never an Intent. */
class HardwareUnlockActivity : BaseActivity() {
    private var generation = -1L
    private lateinit var discovery: HardwareKeyDiscovery
    private lateinit var pin: EditText
    private var progress: AlertDialog? = null

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
        val prompt = AlertDialog.Builder(this).setTitle(R.string.hardware_title).setMessage(R.string.hardware_connect)
            .setNegativeButton(android.R.string.cancel) { _, _ -> discovery.cancel() }.create()
        progress?.dismiss()
        progress = prompt
        prompt.setOnCancelListener { discovery.cancel() }
        prompt.setOnDismissListener { if (progress === prompt) { progress = null; discovery.cancel() } }
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
