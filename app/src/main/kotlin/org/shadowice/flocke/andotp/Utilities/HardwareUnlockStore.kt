package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKey

/** Stores only an authenticated, hardware-PRF-wrapped vault key; no PRF output or password. */
internal object HardwareUnlockStore {
    private const val KEY = "internal_hardware_unlock_v1"
    private const val MAX_ENCODED = 2048

    fun read(context: Context): ByteArray? {
        return try {
            val encoded = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null) ?: return null
            if (encoded.length > MAX_ENCODED) return null
            val bytes = Base64.getDecoder().decode(encoded)
            if (HardwareUnlockEnvelope.request(bytes) == null) null else bytes
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Enroll only for the same live password vault, after verifying the wrapped key. */
    fun save(context: Context, generation: Long, key: SecretKey, envelope: ByteArray, prf: ByteArray,
             permitted: () -> Boolean): Boolean {
        synchronized(VaultSession.MutationLock) {
            if (!permitted() || !VaultSession.isCurrent(generation) || VaultSession.getKey() !== key) return false
            val settings = Settings(context)
            if (settings.getAuthMethod() != Constants.AuthMethod.PASSWORD ||
                settings.getEncryption() != Constants.EncryptionType.PASSWORD) return false
            // SecretKey.encoded may be provider-owned: only wipe a clone.
            val encoded = key.encoded ?: return false
            val original = encoded.clone()
            var recovered: ByteArray? = null
            try {
                recovered = HardwareUnlockEnvelope.open(envelope, prf)
                if (recovered == null || !MessageDigest.isEqual(original, recovered)) return false
                val value = Base64.getEncoder().encodeToString(envelope)
                if (value.length > MAX_ENCODED || !permitted()) return false
                return PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, value).commit()
            } catch (_: RuntimeException) {
                return false
            } finally {
                original.fill(0)
                recovered?.fill(0)
            }
        }
    }

    /** Removing the wrapper never changes the password vault or the hardware credential. */
    fun remove(context: Context, generation: Long, key: SecretKey): Boolean = synchronized(VaultSession.MutationLock) {
        if (!VaultSession.isCurrent(generation) || VaultSession.getKey() !== key) return@synchronized false
        try { PreferenceManager.getDefaultSharedPreferences(context).edit().remove(KEY).commit() }
        catch (_: RuntimeException) { false }
    }

    /** Add to the same atomic preferences transaction as a vault-key change. */
    fun clearOnVaultChange(editor: SharedPreferences.Editor) { editor.remove(KEY) }

    fun clearAfterReset(context: Context) {
        try { PreferenceManager.getDefaultSharedPreferences(context).edit().remove(KEY).commit() }
        catch (_: RuntimeException) { }
    }
}
