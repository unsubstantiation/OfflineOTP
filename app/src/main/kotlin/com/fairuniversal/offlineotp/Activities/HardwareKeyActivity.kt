package com.fairuniversal.offlineotp.Activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.CommandState
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.DatabaseHelper
import com.fairuniversal.offlineotp.Utilities.HardwareFidoClient
import com.fairuniversal.offlineotp.Utilities.HardwareKeyDiscovery
import com.fairuniversal.offlineotp.Utilities.HardwareOathClient
import com.fairuniversal.offlineotp.Utilities.HardwareUnlockEnvelope
import com.fairuniversal.offlineotp.Utilities.HardwareUnlockStore
import com.fairuniversal.offlineotp.Utilities.Tools
import com.fairuniversal.offlineotp.Utilities.VaultReadResult
import com.fairuniversal.offlineotp.Utilities.VaultSession
import com.fairuniversal.offlineotp.ui.host.BusyProgressComposeView
import com.fairuniversal.offlineotp.ui.host.ComposeDialogHost
import com.fairuniversal.offlineotp.ui.host.DeleteConfirmationComposeView
import com.fairuniversal.offlineotp.ui.host.HardwareCopyComposeView
import com.fairuniversal.offlineotp.ui.host.HardwareEnrollmentComposeView

/** Foreground-only OATH account access and optional FIDO2 vault-unlock enrollment. */
class HardwareKeyActivity : BaseActivity() {
    private var generation = -1L
    private lateinit var discovery: HardwareKeyDiscovery
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var accountsView: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var clock: () -> Long = System::currentTimeMillis
    internal var dialog: ComposeDialogHost? = null
    internal var composeDialog: ComposeDialogHost? = null
    private var composeCleanup: (() -> Unit)? = null
    private var listed: HardwareOathClient.Accounts? = null
    private var listRevision = 0

    private class EnrollmentMaterial(val envelope: ByteArray, val prf: ByteArray) : AutoCloseable {
        override fun close() { prf.fill(0) }
        override fun toString(): String = "EnrollmentMaterial(redacted)"
    }

    override fun requiresVaultSession(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = VaultSession.getKey()
        if (key == null) { finish(); return }
        generation = VaultSession.generation()
        discovery = HardwareKeyDiscovery(this) { sessionReady() }
        setTitle(R.string.hardware_title)
        setContentView(R.layout.activity_hardware_keys)
        password = requireNotNull(findViewById(R.id.hardwarePassword))
        password.isSaveEnabled = false
        if (settings?.getBlockAccessibility() == true)
            password.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
        status = requireNotNull(findViewById(R.id.hardwareStatus))
        accountsView = requireNotNull(findViewById(R.id.hardwareAccounts))
        requireNotNull(findViewById<Button>(R.id.hardwareList)).setOnClickListener { listAccounts() }
        requireNotNull(findViewById<Button>(R.id.hardwareCopy)).setOnClickListener { chooseAccount() }
        requireNotNull(findViewById<Button>(R.id.hardwareEnroll)).setOnClickListener { confirmEnrollment() }
        requireNotNull(findViewById<Button>(R.id.hardwareRemove)).setOnClickListener { confirmRemoval() }
    }

    private fun sessionReady(): Boolean = VaultSession.isCurrent(generation) && VaultSession.getKey() != null

    override fun onResume() {
        super.onResume()
        if (::discovery.isInitialized) discovery.resume()
    }

    override fun onPause() {
        if (::discovery.isInitialized) discovery.pause()
        super.onPause()
    }

    override fun onStop() {
        // Permission prompts may pause the activity, but no operation continues after it stops.
        clearSensitiveUi()
        super.onStop()
    }

    override fun onVaultLocked() {
        clearSensitiveUi()
        super.onVaultLocked()
    }

    override fun onDestroy() {
        clearSensitiveUi()
        if (::discovery.isInitialized) discovery.close()
        super.onDestroy()
    }

    private fun clearSensitiveUi() {
        if (::password.isInitialized) password.setText("")
        if (::discovery.isInitialized) discovery.cancel()
        dismissComposeDialog()
        dialog?.dismiss()
        dialog = null
        listed = null
        listRevision++
        if (::accountsView.isInitialized) accountsView.removeAllViews()
        handler.removeCallbacksAndMessages(null)
    }

    /** The operation owns its PIN and optional entry until completion/cancellation. */
    private fun <T> runOperation(protocol: HardwareKeyDiscovery.Protocol, entry: Entry? = null,
                                 explicitPin: CharArray? = null,
                                 work: (YubiKeyDevice, CharArray?, () -> Boolean) -> T,
                                 discard: (T) -> Unit = {}, cancelWork: () -> Unit = {},
                                 interruptedMessage: Int? = null, finished: (T?, Boolean) -> Unit) {
        if (!sessionReady()) { explicitPin?.fill('\u0000'); entry?.destroy(); return }
        dismissComposeDialog()
        val text = password.text
        val chars = CharArray(text?.length ?: 0) { text!![it] }
        password.setText("")
        val pin = explicitPin?.takeIf { it.isNotEmpty() } ?: chars.takeIf { it.isNotEmpty() }
        val progress = BusyProgressComposeView(
            activity = this,
            title = getString(R.string.hardware_title),
            message = getString(R.string.hardware_connect),
            cancelLabel = getString(android.R.string.cancel),
            onCancel = { discovery.cancel() }
        )
        dialog?.dismiss()
        dialog = progress
        progress.setOnDismissListener {
            if (dialog === progress) {
                dialog = null
                discovery.cancel()
                // A canceled hardware command may already have mutated the key.
                if (interruptedMessage != null && sessionReady()) status.setText(interruptedMessage)
            }
        }
        progress.show()
        discovery.start(protocol, { device, permitted -> work(device, pin, permitted) }, discard,
            { chars.fill('\u0000'); explicitPin?.fill('\u0000'); entry?.destroy() }, cancelWork) { result, success ->
            if (dialog === progress) dialog = null
            progress.dismiss()
            if (sessionReady()) finished(result, success) else if (success && result != null) discard(result)
        }
    }

    private fun listAccounts() {
        runOperation(HardwareKeyDiscovery.Protocol.OATH,
            work = { device, pin, permitted -> HardwareOathClient.list(device, pin, permitted) }) { result, success ->
            if (!success || result == null) showFailure() else renderAccounts(result)
        }
    }

    private fun renderAccounts(result: HardwareOathClient.Accounts) {
        listed = result
        val revision = ++listRevision
        handler.removeCallbacksAndMessages(null)
        accountsView.removeAllViews()
        status.setText(if (result.items.isEmpty()) R.string.hardware_empty else R.string.hardware_title)
        val now = clock()
        for (account in result.items) {
            val credential = account.credential
            val name = displayName(credential.issuer.orEmpty(), credential.accountName)
            val validCode = account.code?.takeIf { account.usableAt(now) }
            var stale = false
            val button = Button(this)
            button.setText("$name\n${validCode ?: getString(R.string.hardware_calculate)}")
            button.setOnClickListener {
                if (revision != listRevision || !sessionReady()) return@setOnClickListener
                if (validCode != null && !stale && account.usableAt(clock())) {
                    Tools.copyToClipboard(this, validCode)
                } else if (credential.oathType == com.yubico.yubikit.oath.OathType.HOTP) {
                    confirmHotp(result.deviceId, account, revision)
                } else calculate(result.deviceId, account)
            }
            accountsView.addView(button)
            if (validCode != null && credential.oathType == com.yubico.yubikit.oath.OathType.HOTP) {
                val next = Button(this)
                next.setText(R.string.hardware_hotp_next)
                next.setOnClickListener { confirmHotp(result.deviceId, account, revision) }
                accountsView.addView(next)
            }
            if (validCode != null && credential.oathType == com.yubico.yubikit.oath.OathType.TOTP) {
                // Recheck the whole interval, including when the system clock moves backwards.
                val refresh = object : Runnable {
                    override fun run() {
                        if (revision != listRevision || !sessionReady()) return
                        val current = clock()
                        if (!account.usableAt(current)) {
                            stale = true
                            button.setText("$name\n${getString(R.string.hardware_expired)}")
                        } else {
                            handler.postDelayed(this, (account.validUntil - current).coerceIn(1L, 1000L))
                        }
                    }
                }
                handler.postDelayed(refresh, (account.validUntil - now).coerceIn(1L, 1000L))
            }
        }
    }

    private fun confirmHotp(deviceId: String, account: HardwareOathClient.Account, revision: Int) {
        if (revision != listRevision || !sessionReady()) return
        lateinit var view: DeleteConfirmationComposeView
        view = DeleteConfirmationComposeView(
            activity = this,
            title = getString(R.string.hardware_title),
            message = getString(R.string.hardware_calculate_hotp),
            confirmLabel = getString(android.R.string.ok),
            dismissLabel = getString(android.R.string.cancel),
            onConfirm = {
                dismissComposeDialog(view)
                if (revision == listRevision && sessionReady()) calculate(deviceId, account)
            },
            onDismiss = { dismissComposeDialog(view) }
        )
        showComposeDialog(view)
    }

    private fun calculate(deviceId: String, account: HardwareOathClient.Account) {
        val hotp = account.credential.oathType == com.yubico.yubikit.oath.OathType.HOTP
        if (hotp) {
            // Once the command might be sent, an older displayed HOTP is no longer reliable.
            val prior = listed
            if (prior != null && prior.deviceId == deviceId) {
                renderAccounts(HardwareOathClient.Accounts(deviceId, prior.items.map {
                    if (it.credential == account.credential) HardwareOathClient.Account(it.credential, null, 0) else it
                }))
            }
        }
        runOperation(HardwareKeyDiscovery.Protocol.OATH,
            work = { device, pin, permitted -> HardwareOathClient.calculate(device, pin, deviceId, account.credential, permitted) },
            interruptedMessage = if (hotp) R.string.hardware_hotp_uncertain else null) { result, success ->
            if (!success || result == null) {
                if (hotp) showUncertain(R.string.hardware_hotp_uncertain) else showFailure()
            } else {
                val prior = listed
                if (prior != null && prior.deviceId == deviceId) {
                    renderAccounts(HardwareOathClient.Accounts(deviceId,
                        prior.items.map { if (it.credential == result.credential) result else it }))
                }
            }
        }
    }

    private fun chooseAccount() {
        val key = VaultSession.getKey() ?: return
        val loaded = synchronized(VaultSession.MutationLock) {
            if (!sessionReady() || VaultSession.getKey() !== key) return
            DatabaseHelper.readVault(this, key)
        }
        if (loaded !is VaultReadResult.Loaded) { showFailure(); return }
        openCopyChooser(loaded.takeEntries())
    }

    internal fun openCopyChooser(all: List<Entry>) {
        val candidates = all.filter { HardwareOathClient.canCopy(it) }.toMutableList()
        all.filter { !candidates.contains(it) }.forEach { it.destroy() }
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.hardware_no_candidates, Toast.LENGTH_LONG).show()
            return
        }
        val names = candidates.map { displayName(it.getIssuer(), it.getLabel()) }
        lateinit var copyView: HardwareCopyComposeView
        copyView = HardwareCopyComposeView(
            activity = this,
            labels = names,
            transportNotice = getString(R.string.hardware_copy_warning),
            requireTouchLabel = getString(R.string.hardware_require_touch),
            confirmLabel = getString(android.R.string.ok),
            dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index, typedPin, requireTouch ->
                val selected = candidates.getOrNull(index)
                if (selected != null) candidates.removeAt(index)
                candidates.forEach { it.destroy() }
                candidates.clear()
                dismissComposeDialog(copyView)
                if (selected != null) {
                    confirmCopy(selected, typedPin, requireTouch)
                } else {
                    typedPin.fill('\u0000')
                }
            },
            onDismiss = { dismissComposeDialog(copyView) }
        )
        showComposeDialog(copyView) {
            candidates.forEach { it.destroy() }
            candidates.clear()
        }
    }

    private fun confirmCopy(entry: Entry, typedPin: CharArray? = null, requireTouch: Boolean = false) {
        runOperation(HardwareKeyDiscovery.Protocol.OATH, entry, explicitPin = typedPin,
            work = { device, pin, permitted -> HardwareOathClient.copyToKey(device, pin, entry, requireTouch, permitted); true },
            interruptedMessage = R.string.hardware_copy_uncertain) { _, success ->
            if (success) Toast.makeText(this, R.string.hardware_copy_done, Toast.LENGTH_LONG).show()
            else showUncertain(R.string.hardware_copy_uncertain)
        }
    }

    private fun confirmEnrollment() {
        if (settings?.getAuthMethod() != Constants.AuthMethod.PASSWORD ||
            settings?.getEncryption() != Constants.EncryptionType.PASSWORD) {
            Toast.makeText(this, R.string.hardware_enroll_password_only, Toast.LENGTH_LONG).show()
            return
        }
        lateinit var view: HardwareEnrollmentComposeView
        view = HardwareEnrollmentComposeView(
            activity = this,
            title = getString(R.string.hardware_enroll),
            warningMessage = getString(R.string.hardware_enroll_warning),
            pinLabel = getString(R.string.hardware_password_hint),
            confirmLabel = getString(android.R.string.ok),
            dismissLabel = getString(android.R.string.cancel),
            onConfirm = { typedPin ->
                dismissComposeDialog(view)
                enroll(typedPin)
            },
            onDismiss = { dismissComposeDialog(view) }
        )
        showComposeDialog(view)
    }

    private fun enroll(typedPin: CharArray? = null) {
        val key = VaultSession.getKey() ?: run { typedPin?.fill('\u0000'); return }
        val state = CommandState()
        runOperation(HardwareKeyDiscovery.Protocol.FIDO, explicitPin = typedPin,
            work = { device, pin, permitted ->
                HardwareFidoClient.enroll(device, pin, state, permitted).use { enrollment ->
                    if (!permitted()) throw IllegalStateException("Canceled")
                    val encoded = key.encoded ?: throw IllegalStateException("Vault key is not exportable")
                    val raw = encoded.clone()
                    try {
                        val envelope = HardwareUnlockEnvelope.seal(enrollment.credentialId, enrollment.salt, enrollment.prf, raw)
                        EnrollmentMaterial(envelope, enrollment.prf.clone())
                    } finally { raw.fill(0) }
                }
            }, discard = { it.close() }, cancelWork = { state.cancel() }) { result, success ->
            // Completion and cancellation are serialized on the main thread. Never persist a
            // worker result after its prompt was canceled; the store rechecks the live vault.
            val saved = result?.use {
                success && HardwareUnlockStore.save(this, generation, key, it.envelope, it.prf) { sessionReady() }
            } == true
            if (!saved) showFailure()
            else Toast.makeText(this, R.string.hardware_enroll_done, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmRemoval() {
        lateinit var view: DeleteConfirmationComposeView
        view = DeleteConfirmationComposeView(
            activity = this,
            title = getString(R.string.hardware_remove),
            message = getString(R.string.hardware_remove_confirm),
            confirmLabel = getString(android.R.string.ok),
            dismissLabel = getString(android.R.string.cancel),
            onConfirm = {
                dismissComposeDialog(view)
                val key = VaultSession.getKey()
                val removed = key != null && HardwareUnlockStore.remove(this, generation, key)
                Toast.makeText(this, if (removed) R.string.hardware_remove_done else R.string.hardware_failed,
                    Toast.LENGTH_LONG).show()
            },
            onDismiss = { dismissComposeDialog(view) }
        )
        showComposeDialog(view)
    }

    /** Account labels may come from a key or QR; controls must not reorder or spoof the picker. */
    private fun displayName(issuer: String, account: String): String =
        listOf(issuer, account).filter(String::isNotBlank).joinToString(" / ")
            .map { if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }
            .joinToString("").trim().take(256).ifEmpty { getString(R.string.hardware_unnamed) }

    private fun showFailure() = Toast.makeText(this, R.string.hardware_failed, Toast.LENGTH_LONG).show()

    private fun showUncertain(message: Int) {
        status.setText(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showComposeDialog(value: ComposeDialogHost, cleanup: () -> Unit = {}) {
        dismissComposeDialog()
        dialog?.dismiss()
        dialog = null
        composeDialog = value
        composeCleanup = cleanup
        value.show()
    }

    private fun dismissComposeDialog(target: ComposeDialogHost? = composeDialog) {
        val current = target ?: return
        if (composeDialog === current) {
            composeDialog = null
            val cleanup = composeCleanup
            composeCleanup = null
            cleanup?.invoke()
        }
        current.dismiss()
    }
}
