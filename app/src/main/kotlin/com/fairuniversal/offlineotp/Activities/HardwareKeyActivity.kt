package com.fairuniversal.offlineotp.Activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import com.fairuniversal.offlineotp.ui.host.HardwareKeysComposeView
import com.fairuniversal.offlineotp.ui.portable.PortableToolGroup
import com.fairuniversal.offlineotp.ui.portable.PortableToolItem
import com.fairuniversal.offlineotp.ui.host.BusyProgressComposeView
import com.fairuniversal.offlineotp.ui.host.ComposeDialogHost
import com.fairuniversal.offlineotp.ui.host.DeleteConfirmationComposeView
import com.fairuniversal.offlineotp.ui.host.HardwareOathRenameComposeView
import com.fairuniversal.offlineotp.ui.host.HardwareOathDeleteComposeView
import com.fairuniversal.offlineotp.ui.host.HardwareCopyComposeView
import com.fairuniversal.offlineotp.ui.host.HardwareEnrollmentComposeView

/** Foreground-only OATH account access and optional FIDO2 vault-unlock enrollment. */
class HardwareKeyActivity : BaseActivity() {
    private var generation = -1L
    private var hardwarePage: HardwareKeysComposeView? = null
    private var deviceBusy = false
    private lateinit var discovery: HardwareKeyDiscovery
    private var hardwareStatus = ""
    private val expiredAccounts = mutableSetOf<Int>()
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
        val container = requireNotNull(findViewById<ViewGroup>(R.id.hardwareComposeContainer))
        val page = HardwareKeysComposeView(this, ::dispatchHardwarePageAction)
        if (settings?.getBlockAccessibility() == true)
            page.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
        hardwarePage = page
        container.addView(page, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        publishHardwarePage()
    }

    /** Project native state directly into common Compose; no hidden Views or proxy clicks. */
    private fun publishHardwarePage() {
        val page = hardwarePage ?: return
        if (!sessionReady()) { page.clear(); return }
        val enabled = !deviceBusy
        val accounts = listed?.items?.flatMapIndexed { index, account ->
            val name = displayName(account.credential.issuer.orEmpty(), account.credential.accountName)
            val code = account.code?.takeIf { index !in expiredAccounts && account.usableAt(clock()) }
            buildList {
                add(PortableToolItem("account:$listRevision:$index", "$name\n${code ?: getString(R.string.hardware_calculate)}", enabled = enabled))
                add(PortableToolItem("delete:$listRevision:$index", getString(R.string.hardware_delete_account, name), enabled = enabled))
                add(PortableToolItem("rename:$listRevision:$index", getString(R.string.hardware_rename_account, name), enabled = enabled))
                if (code != null && account.credential.oathType == com.yubico.yubikit.oath.OathType.HOTP)
                    add(PortableToolItem("next:$listRevision:$index", getString(R.string.hardware_hotp_next), enabled = enabled))
            }
        }.orEmpty()
        page.render(
            title = getString(R.string.hardware_title), introduction = getString(R.string.hardware_intro),
            passwordLabel = getString(R.string.hardware_oath_password_hint),
            groups = listOf(PortableToolGroup("", listOf(
                PortableToolItem("list", getString(R.string.hardware_list), enabled = enabled),
                PortableToolItem("copy", getString(R.string.hardware_copy), enabled = enabled),
                PortableToolItem("enroll", getString(R.string.hardware_enroll), enabled = enabled),
                PortableToolItem("remove", getString(R.string.hardware_remove), enabled = enabled)
            )), PortableToolGroup(getString(R.string.hardware_list), accounts)),
            status = hardwareStatus, busy = deviceBusy)
    }

    private fun dispatchHardwarePageAction(key: String, typedPassword: CharArray) {
        try {
            if (!sessionReady() || deviceBusy || composeDialog != null || dialog != null) return
            when (key) {
                "list" -> listAccounts(typedPassword.copyOf())
                "copy" -> chooseAccount()
                "enroll" -> confirmEnrollment()
                "remove" -> confirmRemoval()
                else -> {
                    val parts = key.split(':')
                    if (parts.size != 3 || parts[1].toIntOrNull() != listRevision) return
                    val index = parts[2].toIntOrNull() ?: return
                    val accounts = listed ?: return
                    val account = accounts.items.getOrNull(index) ?: return
                    when (parts[0]) {
                        "delete" -> confirmAccountDeletion(accounts.deviceId, account, listRevision)
                        "rename" -> confirmAccountRename(accounts.deviceId, account, listRevision)
                        "next" -> if (account.credential.oathType == com.yubico.yubikit.oath.OathType.HOTP)
                            confirmHotp(accounts.deviceId, account, listRevision, typedPassword.copyOf())
                        "account" -> {
                            val code = account.code?.takeIf { index !in expiredAccounts && account.usableAt(clock()) }
                            if (code != null) Tools.copyToClipboard(this, code)
                            else if (account.credential.oathType == com.yubico.yubikit.oath.OathType.HOTP)
                                confirmHotp(accounts.deviceId, account, listRevision, typedPassword.copyOf())
                            else calculate(accounts.deviceId, account, typedPassword.copyOf())
                        }
                    }
                }
            }
        } finally { typedPassword.fill('\u0000') }
    }

    private fun sessionReady(): Boolean = VaultSession.isCurrent(generation) && VaultSession.getKey() != null

    override fun onResume() {
        super.onResume()
        if (::discovery.isInitialized) discovery.resume()
        if (sessionReady()) publishHardwarePage()
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
        hardwarePage?.close()
        hardwarePage = null
        if (::discovery.isInitialized) discovery.close()
        super.onDestroy()
    }

    private fun clearSensitiveUi() {
        hardwarePage?.clear()
        deviceBusy = false
        if (::discovery.isInitialized) discovery.cancel()
        dismissComposeDialog()
        dialog?.dismiss()
        dialog = null
        listed = null
        listRevision++
        expiredAccounts.clear()
        hardwareStatus = ""
        handler.removeCallbacksAndMessages(null)
        hardwarePage?.clear()
    }

    /** The operation owns its PIN and optional entry until completion/cancellation. */
    private fun <T> runOperation(protocol: HardwareKeyDiscovery.Protocol, entry: Entry? = null,
                                 explicitPin: CharArray? = null,
                                 work: (YubiKeyDevice, CharArray?, () -> Boolean) -> T,
                                 discard: (T) -> Unit = {}, cancelWork: () -> Unit = {},
                                 interruptedMessage: Int? = null, finished: (T?, Boolean) -> Unit) {
        if (!sessionReady()) { explicitPin?.fill('\u0000'); entry?.destroy(); return }
        dismissComposeDialog()
        val pin = explicitPin?.takeIf { it.isNotEmpty() }
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
                if (interruptedMessage != null && sessionReady()) showHardwareStatus(interruptedMessage)
                deviceBusy = false
                if (sessionReady()) publishHardwarePage()
            }
        }
        deviceBusy = true
        publishHardwarePage()
        progress.show()
        discovery.start(protocol, { device, permitted -> work(device, pin, permitted) }, discard,
            { explicitPin?.fill('\u0000'); entry?.destroy() }, cancelWork) { result, success ->
            if (dialog === progress) dialog = null
            progress.dismiss()
            deviceBusy = false
            if (sessionReady()) {
                finished(result, success)
                publishHardwarePage()
            } else if (success && result != null) discard(result)
        }
    }

    private fun listAccounts(pin: CharArray) {
        runOperation(HardwareKeyDiscovery.Protocol.OATH, explicitPin = pin,
            work = { device, pin, permitted -> HardwareOathClient.list(device, pin, permitted) }) { result, success ->
            if (!success || result == null) showFailure() else renderAccounts(result)
        }
    }

    private fun renderAccounts(result: HardwareOathClient.Accounts) {
        listed = result
        val revision = ++listRevision
        expiredAccounts.clear()
        handler.removeCallbacksAndMessages(null)
        hardwareStatus = getString(if (result.items.isEmpty()) R.string.hardware_empty else R.string.hardware_title)
        val now = clock()
        result.items.forEachIndexed { index, account ->
            if (account.code != null && account.credential.oathType == com.yubico.yubikit.oath.OathType.TOTP) {
                if (!account.usableAt(now)) expiredAccounts.add(index)
                else {
                    val refresh = object : Runnable {
                        override fun run() {
                            if (revision != listRevision || !sessionReady()) return
                            val current = clock()
                            if (!account.usableAt(current)) {
                                expiredAccounts.add(index); publishHardwarePage()
                            } else handler.postDelayed(this, (account.validUntil - current).coerceIn(1L, 1000L))
                        }
                    }
                    handler.postDelayed(refresh, (account.validUntil - now).coerceIn(1L, 1000L))
                }
            }
        }
        publishHardwarePage()
    }

    private fun confirmAccountRename(deviceId: String, account: HardwareOathClient.Account, revision: Int) {
        if (revision != listRevision || !sessionReady() || deviceBusy) return
        lateinit var view: HardwareOathRenameComposeView
        view = HardwareOathRenameComposeView(this,
            displayName(account.credential.issuer.orEmpty(), account.credential.accountName),
            onConfirm = { pin, issuer, name ->
                val ownsPrompt = composeDialog === view
                dismissComposeDialog(view)
                if (!ownsPrompt || revision != listRevision || !sessionReady()) pin.fill('\u0000')
                else if (com.fairuniversal.offlineotp.Utilities.HardwareOathRenamePolicy.names(
                        account.credential.oathType == com.yubico.yubikit.oath.OathType.HOTP,
                        account.credential.period, issuer, name) == null) {
                    pin.fill('\u0000'); showHardwareStatus(R.string.hardware_rename_invalid)
                } else {
                    renderAccounts(HardwareOathClient.Accounts(deviceId, emptyList()))
                    runOperation(HardwareKeyDiscovery.Protocol.OATH, explicitPin = pin,
                        work = { device, password, permitted ->
                            HardwareOathClient.renameOnKey(device, password, deviceId, account.credential, issuer, name, true, permitted)
                            true
                        }, interruptedMessage = R.string.hardware_rename_uncertain) { _, success ->
                        showHardwareStatus(if (success) R.string.hardware_rename_done else R.string.hardware_rename_uncertain)
                    }
                }
            }, onDismiss = { dismissComposeDialog(view) })
        showComposeDialog(view)
    }

    private fun confirmAccountDeletion(deviceId: String, account: HardwareOathClient.Account, revision: Int) {
        if (revision != listRevision || !sessionReady() || deviceBusy) return
        lateinit var view: HardwareOathDeleteComposeView
        view = HardwareOathDeleteComposeView(this,
            displayName(account.credential.issuer.orEmpty(), account.credential.accountName),
            onConfirm = { pin ->
                val ownsPrompt = composeDialog === view
                dismissComposeDialog(view)
                if (!ownsPrompt || revision != listRevision || !sessionReady()) pin.fill('\u0000')
                else {
                    // Retire stale codes/account selection even when completion is uncertain or cancelled.
                    renderAccounts(HardwareOathClient.Accounts(deviceId, emptyList()))
                    runOperation(HardwareKeyDiscovery.Protocol.OATH, explicitPin = pin,
                        work = { device, password, permitted ->
                            HardwareOathClient.deleteFromKey(device, password, deviceId, account.credential, true, permitted)
                            true
                        }, interruptedMessage = R.string.hardware_delete_uncertain) { _, success ->
                        showHardwareStatus(if (success) R.string.hardware_delete_done else R.string.hardware_delete_uncertain)
                    }
                }
            }, onDismiss = { dismissComposeDialog(view) })
        showComposeDialog(view)
    }

    private fun confirmHotp(deviceId: String, account: HardwareOathClient.Account, revision: Int, pin: CharArray) {
        if (revision != listRevision || !sessionReady()) { pin.fill('\u0000'); return }
        var owned: CharArray? = pin
        lateinit var view: DeleteConfirmationComposeView
        view = DeleteConfirmationComposeView(
            activity = this, title = getString(R.string.hardware_title), message = getString(R.string.hardware_calculate_hotp),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = {
                val password = owned; owned = null
                dismissComposeDialog(view)
                if (password != null) {
                    if (revision == listRevision && sessionReady()) calculate(deviceId, account, password)
                    else password.fill('\u0000')
                }
            }, onDismiss = { dismissComposeDialog(view) })
        showComposeDialog(view) { owned?.fill('\u0000'); owned = null }
    }

    private fun calculate(deviceId: String, account: HardwareOathClient.Account, pin: CharArray) {
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
        runOperation(HardwareKeyDiscovery.Protocol.OATH, explicitPin = pin,
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
            showHardwareStatus(R.string.hardware_no_candidates)
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
            if (success) showHardwareStatus(R.string.hardware_copy_done)
            else showUncertain(R.string.hardware_copy_uncertain)
        }
    }

    private fun confirmEnrollment() {
        if (settings?.getAuthMethod() != Constants.AuthMethod.PASSWORD ||
            settings?.getEncryption() != Constants.EncryptionType.PASSWORD) {
            showHardwareStatus(R.string.hardware_enroll_password_only)
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
            else showHardwareStatus(R.string.hardware_enroll_done)
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
                showHardwareStatus(if (removed) R.string.hardware_remove_done else R.string.hardware_failed)
            },
            onDismiss = { dismissComposeDialog(view) }
        )
        showComposeDialog(view)
    }

    /** Account labels may come from a key or QR; controls must not reorder or spoof the picker. */
    private fun displayName(issuer: String, account: String): String =
        listOf(issuer, account).filter(String::isNotBlank).joinToString(" / ")
            .map { if (it.isISOControl() || it == '\u2028' || it == '\u2029' || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }
            .joinToString("").trim().take(256).ifEmpty { getString(R.string.hardware_unnamed) }

    private fun showFailure() = showHardwareStatus(R.string.hardware_failed)

    private fun showUncertain(message: Int) = showHardwareStatus(message)

    private fun showHardwareStatus(message: Int) {
        if (!sessionReady()) return
        hardwareStatus = getString(message)
        publishHardwarePage()
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
