package org.shadowice.flocke.andotp.desktop.control

import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.CommandState
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.HardwareFidoClient
import org.shadowice.flocke.andotp.Utilities.HardwareOathClient
import org.shadowice.flocke.andotp.Utilities.HardwareUnlockEnvelope
import org.shadowice.flocke.andotp.Utilities.HardwareUnlockStoragePort
import org.shadowice.flocke.andotp.desktop.hardware.HardwareDeviceSelector
import org.shadowice.flocke.andotp.desktop.hardware.HardwareDeviceSource

import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Headless security-key workflows for the desktop client, mirroring the Android
 * HardwareKeyActivity/HardwareUnlockActivity contracts without any Android or Swing types:
 *  - OATH account listing, explicit HOTP calculation and vault-entry copy-to-key;
 *  - FIDO2 PRF enrollment that seals the current vault key into the persisted envelope, and
 *    hardware unlock that recovers the key through the PRF.
 *
 * Every operation runs through [withDevice]: a missing key, cancellation or any provider failure
 * becomes a typed [Result] with no device-derived message, matching the Android rule that
 * exception text may carry key or account data. The Swing layer runs these off the event thread
 * with a [CancelToken]; headless tests inject a fake [HardwareDeviceSource].
 */
class HardwareKeyManager(
    private val source: HardwareDeviceSource,
    private val unlockStore: HardwareUnlockStoragePort
) {

    /** Cancellation handle shared by the UI progress dialog and the running hardware command. */
    class CancelToken {
        @Volatile
        private var canceled = false
        private val hooks = ArrayList<() -> Unit>()

        fun isCanceled(): Boolean = canceled

        /** Registers a low-level cancel hook (e.g. a yubikit CommandState); runs immediately if
         *  the token was already canceled. */
        fun addCancelHook(hook: () -> Unit) {
            val runNow = synchronized(hooks) {
                if (canceled) true else { hooks.add(hook); false }
            }
            if (runNow) runCatching(hook)
        }

        fun cancel() {
            val pending = synchronized(hooks) {
                canceled = true
                ArrayList(hooks)
            }
            pending.forEach { runCatching(it) }
        }
    }

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        object NoKey : Result<Nothing>()
        object Canceled : Result<Nothing>()
        object Failed : Result<Nothing>()
    }

    /** True when a hardware-unlock envelope is enrolled for this vault directory. */
    fun isEnrolled(): Boolean = unlockStore.readEnvelope() != null

    fun listOathAccounts(pin: CharArray?, token: CancelToken): Result<HardwareOathClient.Accounts> =
        withDevice(pin, token) { device, permitted ->
            HardwareOathClient.list(device, pin, permitted)
        }

    fun calculateOath(
        pin: CharArray?, deviceId: String, account: HardwareOathClient.Account, token: CancelToken
    ): Result<HardwareOathClient.Account> =
        withDevice(pin, token) { device, permitted ->
            HardwareOathClient.calculate(device, pin, deviceId, account.credential, permitted)
        }

    fun copyEntryToKey(
        pin: CharArray?, entry: Entry, requireTouch: Boolean, token: CancelToken
    ): Result<Unit> =
        withDevice(pin, token) { device, permitted ->
            HardwareOathClient.copyToKey(device, pin, entry, requireTouch, permitted)
        }

    /**
     * Enrolls the current session's vault [vaultKey] behind the key's FIDO2 PRF. The envelope is
     * only persisted after an authenticated round-trip recovers the exact key bytes, matching the
     * Android HardwareUnlockStore.save verification.
     */
    fun enroll(pin: CharArray?, vaultKey: SecretKey, token: CancelToken): Result<Unit> =
        withDevice(pin, token) { device, permitted ->
            val commandState = CommandState().also { token.addCancelHook(it::cancel) }
            HardwareFidoClient.enroll(device, pin, commandState, permitted).use { enrollment ->
                if (!permitted()) throw IllegalStateException("Canceled")
                val raw = vaultKey.encoded ?: throw IllegalStateException("Vault key is not exportable")
                val keyBytes = raw.clone()
                try {
                    val envelope = HardwareUnlockEnvelope.seal(
                        enrollment.credentialId, enrollment.salt, enrollment.prf, keyBytes)
                    val recovered = HardwareUnlockEnvelope.open(envelope, enrollment.prf)
                    if (recovered == null || !MessageDigest.isEqual(recovered, keyBytes)) {
                        throw IllegalStateException("Hardware enrollment verification failed")
                    }
                    if (!unlockStore.writeEnvelope(envelope)) {
                        throw IllegalStateException("Hardware envelope persistence failed")
                    }
                } finally {
                    keyBytes.fill(0)
                }
            }
        }

    fun removeEnrollment(): Boolean {
        if (unlockStore.readEnvelope() == null) return false
        unlockStore.removeEnvelope()
        return true
    }

    /**
     * Recovers the vault key from the enrolled envelope and the key's PRF. The returned key owns
     * its own byte copy; PRF and intermediates are wiped before returning.
     */
    fun unlockWithKey(pin: CharArray?, token: CancelToken): Result<SecretKey> {
        val envelope = unlockStore.readEnvelope() ?: run {
            pin?.fill(' ')
            return Result.Failed
        }
        val request = HardwareUnlockEnvelope.request(envelope) ?: run {
            pin?.fill(' ')
            return Result.Failed
        }
        return withDevice(pin, token) { device, permitted ->
            val commandState = CommandState().also { token.addCancelHook(it::cancel) }
            val prf = HardwareFidoClient.calculate(device, request, pin, commandState)
            try {
                val raw = HardwareUnlockEnvelope.open(envelope, prf)
                    ?: throw IllegalStateException("Invalid hardware key")
                try {
                    SecretKeySpec(raw, "AES")
                } finally {
                    raw.fill(0)
                }
            } finally {
                prf.fill(0)
            }
        }
    }

    fun close() {
        runCatching { source.close() }
    }

    private fun <T> withDevice(
        pin: CharArray?,
        token: CancelToken,
        work: (YubiKeyDevice, () -> Boolean) -> T
    ): Result<T> {
        try {
            if (token.isCanceled()) return Result.Canceled
            val device = HardwareDeviceSelector.firstAvailable(source) ?: return Result.NoKey
            val permitted = { !token.isCanceled() }
            return try {
                Result.Ok(work(device, permitted))
            } catch (_: IllegalStateException) {
                if (token.isCanceled()) Result.Canceled else Result.Failed
            } catch (_: Exception) {
                // Never propagate provider messages: they can include key/account material.
                Result.Failed
            }
        } finally {
            // The caller hands over ownership of the PIN array for the duration of one operation;
            // yubikit only reads it transiently, so wipe it on every path (including no-key/cancel).
            pin?.fill(' ')
        }
    }
}
