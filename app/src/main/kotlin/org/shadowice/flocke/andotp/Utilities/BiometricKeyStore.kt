package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.preference.PreferenceManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint quick unlock, mirroring the iOS Face ID quick unlock.
 *
 * The vault stays password-encrypted (PBKDF2 + AES-GCM). What fingerprint releases is the
 * *derived vault key* — the 16-byte AES key that the password derives. The vault file, KDF,
 * salt, verifier and GCM tag are untouched; the password still opens the file and a lost or
 * invalidated shortcut costs only the shortcut.
 *
 * Storage: a KeyStore AES key with `setUserAuthenticationRequired(true)` and
 * `setInvalidatedByBiometricEnrollment(true)` encrypts the vault key into SharedPreferences.
 * The KeyStore key never leaves the TEE/StrongBox; the preference holds only IV + ciphertext.
 * No sync, no backup, `WhenUnlockedThisDeviceOnly` semantics via the KeyStore.
 */
object BiometricKeyStore {
    private const val KEYSTORE_ALIAS = "offlineotp_biometric_key_v1"
    private const val PREF_ENCRYPTED_KEY = "biometric_encrypted_vault_key_v1"
    private const val MAX_ENCODED = 4096

    // Preference key for the toggle — stored beside ui_sort_mode, never inside secrets.dat.
    fun prefKeyEnabled(context: Context): String =
        context.getString(org.shadowice.flocke.andotp.R.string.settings_key_biometric_enabled)

    fun isEnabled(context: Context): Boolean {
        return try {
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(prefKeyEnabled(context), false)
        } catch (_: RuntimeException) { false }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(prefKeyEnabled(context), enabled).apply()
        } catch (_: RuntimeException) { }
    }

    fun hasStoredKey(context: Context): Boolean {
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val encoded = prefs.getString(PREF_ENCRYPTED_KEY, null)
            encoded != null && encoded.isNotEmpty() && encoded.length <= MAX_ENCODED
        } catch (_: RuntimeException) { false }
    }

    /** Whether fingerprint unlock can be offered right now. */
    enum class Availability {
        AVAILABLE,
        NOT_ENROLLED,
        UNAVAILABLE;

        val isAvailable: Boolean get() = this == AVAILABLE

        val refusal: String
            get() = when (this) {
                AVAILABLE -> ""
                NOT_ENROLLED -> BiometricWording.notEnrolled
                UNAVAILABLE -> BiometricWording.unavailable
            }
    }

    enum class BiometricError {
        UNAVAILABLE,
        NOT_ENROLLED,
        CANCELLED,
        LOCKED_OUT,
        FAILED,
        MISSING;

        val message: String
            get() = when (this) {
                UNAVAILABLE -> BiometricWording.unavailable
                NOT_ENROLLED -> BiometricWording.notEnrolled
                CANCELLED -> BiometricWording.cancelled
                LOCKED_OUT -> BiometricWording.lockedOut
                FAILED, MISSING -> BiometricWording.stale
            }
    }

    object BiometricWording {
        const val reason = "Unlock your OfflineOTP vault."
        const val cancel = "Use password"
        const val switchTitle = "Unlock with fingerprint"
        const val switchDetail =
            "Unlock this vault with fingerprint instead of typing the password. The vault stays encrypted " +
                "with the password: fingerprint only releases a key this device keeps, and the password still " +
                "works. A second account on this device cannot use it."
        const val enableQuestion = "Enter the vault password to turn this on."
        const val wrongPassword = "That password does not open this vault."
        const val saveFailed = "This device could not keep the unlock key. Try again."
        const val unavailable = "This device cannot use fingerprint unlock."
        const val notEnrolled = "Set up fingerprint on this device first."
        const val cancelled = "Fingerprint unlock was cancelled. Enter your password."
        const val lockedOut = "Too many attempts. Enter your password to unlock."
        const val stale =
            "Fingerprint unlock is no longer set up for this vault. Enter your password, then turn it on again."
        const val turnedOn = "Fingerprint unlock is on for this vault."
        const val turnedOff = "Fingerprint unlock is off."
    }

    fun availability(context: Context): Availability {
        return try {
            val manager = BiometricManager.from(context)
            when (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
                else -> Availability.UNAVAILABLE
            }
        } catch (_: RuntimeException) {
            Availability.UNAVAILABLE
        } catch (_: Exception) {
            Availability.UNAVAILABLE
        }
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
                entry?.secretKey ?: return null
            } else {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val builder = KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                if (Build.VERSION.SDK_INT >= 30) {
                    builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    builder.setUserAuthenticationValidityDurationSeconds(-1)
                }
                keyGenerator.init(builder.build())
                keyGenerator.generateKey()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getExistingSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) return null
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Enrollment changed — key is permanently invalidated.
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                ks.deleteEntry(KEYSTORE_ALIAS)
            } catch (_: Exception) { }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteKeystoreKey() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
        } catch (_: Exception) { }
    }

    /** Remove stored vault key (and optionally the toggle) from preferences. */
    fun clearStoredKey(context: Context) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(PREF_ENCRYPTED_KEY).apply()
        } catch (_: RuntimeException) { }
    }

    fun clear(context: Context) {
        clearStoredKey(context)
        setEnabled(context, false)
        deleteKeystoreKey()
    }

    /** For atomic vault-change transactions: remove biometric shortcut without needing Context. */
    fun clearOnVaultChange(editor: SharedPreferences.Editor) {
        editor.remove(PREF_ENCRYPTED_KEY)
        editor.remove(prefKeyEnabledPlaceholder())
        // KeyStore key is left to be invalidated lazily; the ciphertext is gone so it is harmless.
        // A full delete would need a Context, so the next save/load will notice invalidation.
    }

    // Placeholder key name when no Context is available — must match settings.xml entry.
    private fun prefKeyEnabledPlaceholder(): String = "pref_biometric_enabled"

    fun clearAfterReset(context: Context) {
        clear(context)
    }

    /**
     * Enroll: encrypt [vaultKey] behind a biometric prompt and store it.
     * Calls [onResult] on the main thread with true on success.
     */
    fun save(activity: FragmentActivity, vaultKey: ByteArray, onResult: (Boolean, BiometricError?) -> Unit) {
        val avail = availability(activity)
        if (!avail.isAvailable) {
            onResult(false, if (avail == Availability.NOT_ENROLLED) BiometricError.NOT_ENROLLED else BiometricError.UNAVAILABLE)
            return
        }
        if (vaultKey.isEmpty()) {
            onResult(false, BiometricError.FAILED)
            return
        }
        val secretKey = getOrCreateSecretKey()
        if (secretKey == null) {
            onResult(false, BiometricError.FAILED)
            return
        }
        val cipher: Cipher
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear(activity)
            onResult(false, BiometricError.MISSING)
            return
        } catch (_: Exception) {
            onResult(false, BiometricError.FAILED)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authCipher = result.cryptoObject?.cipher ?: cipher
                try {
                    val ciphertext = authCipher.doFinal(vaultKey)
                    val iv = authCipher.iv ?: run {
                        onResult(false, BiometricError.FAILED)
                        return
                    }
                    if (iv.size != 12) {
                        onResult(false, BiometricError.FAILED)
                        return
                    }
                    val combined = ByteArray(iv.size + ciphertext.size)
                    System.arraycopy(iv, 0, combined, 0, iv.size)
                    System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
                    val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
                    if (encoded.length > MAX_ENCODED) {
                        onResult(false, BiometricError.FAILED)
                        return
                    }
                    try {
                        PreferenceManager.getDefaultSharedPreferences(activity).edit()
                            .putString(PREF_ENCRYPTED_KEY, encoded)
                            .putBoolean(prefKeyEnabled(activity), true)
                            .apply()
                    } catch (_: RuntimeException) {
                        onResult(false, BiometricError.FAILED)
                        return
                    }
                    onResult(true, null)
                } catch (_: Exception) {
                    onResult(false, BiometricError.FAILED)
                } finally {
                    // Wipe the copy the caller gave us? Caller owns it; we don't fill it here
                    // because they may need to retry. But we wipe our local combined/ciphertext
                    // arrays implicitly via GC; not perfect but matches iOS non-swappable.
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val mapped = when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> BiometricError.CANCELLED
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricError.LOCKED_OUT
                    BiometricPrompt.ERROR_NO_BIOMETRICS,
                    BiometricPrompt.ERROR_HW_NOT_PRESENT,
                    BiometricPrompt.ERROR_HW_UNAVAILABLE,
                    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> {
                        // Distinguish not-enrolled when possible
                        if (availability(activity) == Availability.NOT_ENROLLED) BiometricError.NOT_ENROLLED
                        else BiometricError.UNAVAILABLE
                    }
                    else -> BiometricError.FAILED
                }
                onResult(false, mapped)
            }

            override fun onAuthenticationFailed() {
                // Per-attempt failure, not terminal — system will keep prompting.
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(switchTitle)
            .setSubtitle("Confirm fingerprint to enable quick unlock")
            .setNegativeButtonText(cancel)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        try {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) {
            onResult(false, BiometricError.FAILED)
        }
    }

    /**
     * Unlock: prompt for biometric and decrypt the stored vault key.
     * Calls [onResult] on the main thread with the vault key or a [BiometricError].
     */
    fun load(activity: FragmentActivity, onResult: (Result<ByteArray>) -> Unit) {
        val avail = availability(activity)
        if (!avail.isAvailable) {
            onResult(Result.failure(RuntimeException(if (avail == Availability.NOT_ENROLLED) BiometricError.NOT_ENROLLED.name else BiometricError.UNAVAILABLE.name)))
            // Map to typed error via wrapper below — caller will reify.
            // We use exception message to carry enum name for simplicity.
            return
        }
        val encoded: String?
        try {
            encoded = PreferenceManager.getDefaultSharedPreferences(activity).getString(PREF_ENCRYPTED_KEY, null)
        } catch (_: RuntimeException) {
            onResult(Result.failure(RuntimeException(BiometricError.FAILED.name)))
            return
        }
        if (encoded.isNullOrEmpty()) {
            onResult(Result.failure(RuntimeException(BiometricError.MISSING.name)))
            return
        }
        val combined: ByteArray
        try {
            combined = Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: Exception) {
            clearStoredKey(activity)
            setEnabled(activity, false)
            onResult(Result.failure(RuntimeException(BiometricError.MISSING.name)))
            return
        }
        if (combined.size <= 12 || combined.size > MAX_ENCODED) {
            clearStoredKey(activity)
            setEnabled(activity, false)
            onResult(Result.failure(RuntimeException(BiometricError.MISSING.name)))
            return
        }
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)

        val secretKey = getExistingSecretKey()
        if (secretKey == null) {
            // Key invalidated or missing — stale enrollment.
            clearStoredKey(activity)
            setEnabled(activity, false)
            onResult(Result.failure(RuntimeException(BiometricError.MISSING.name)))
            return
        }

        val cipher: Cipher
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear(activity)
            onResult(Result.failure(RuntimeException(BiometricError.MISSING.name)))
            return
        } catch (_: Exception) {
            onResult(Result.failure(RuntimeException(BiometricError.FAILED.name)))
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authCipher = result.cryptoObject?.cipher ?: cipher
                try {
                    val plain = authCipher.doFinal(ciphertext)
                    // Plain should be 16/24/32 bytes (AES key). Validate.
                    if (plain.isEmpty() || plain.size !in listOf(16, 24, 32)) {
                        plain.fill(0)
                        clearStoredKey(activity)
                        setEnabled(activity, false)
                        onResult(Result.failure(RuntimeException(BiometricError.MISSING.name)))
                        return
                    }
                    // Verify the derived key actually opens the vault before returning?
                    // We don't have DB access here; caller will try VaultSession.activate and handle GCM failure.
                    // But we can do a basic check that the key is plausible.
                    onResult(Result.success(plain))
                } catch (e: Exception) {
                    // Decryption failed — likely invalidated key or corrupted blob.
                    val isInvalidated = e is KeyPermanentlyInvalidatedException ||
                        e.cause is KeyPermanentlyInvalidatedException
                    if (isInvalidated) {
                        clear(activity)
                    } else {
                        // Corrupted blob? Clear to avoid loop.
                        try { clearStoredKey(activity); setEnabled(activity, false) } catch (_: Exception) { }
                    }
                    onResult(Result.failure(RuntimeException(BiometricError.MISSING.name)))
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val mapped = when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> BiometricError.CANCELLED
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricError.LOCKED_OUT
                    BiometricPrompt.ERROR_NO_BIOMETRICS,
                    BiometricPrompt.ERROR_HW_NOT_PRESENT,
                    BiometricPrompt.ERROR_HW_UNAVAILABLE -> BiometricError.UNAVAILABLE
                    else -> BiometricError.FAILED
                }
                // For stale/invalidated, BiometricPrompt may report FAILED; our decrypt path handles MISSING.
                // Here we report the mapped error; caller will show appropriate toast.
                // Encode enum name in exception for typed handling.
                onResult(Result.failure(RuntimeException(mapped.name)))
            }

            override fun onAuthenticationFailed() { }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(switchTitle)
            .setSubtitle(reason)
            .setNegativeButtonText(cancel)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        try {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) {
            onResult(Result.failure(RuntimeException(BiometricError.FAILED.name)))
        }
    }

    /** Helper to map Result failure message back to BiometricError. */
    fun errorFromResult(result: Result<ByteArray>): BiometricError? {
        if (result.isSuccess) return null
        val name = result.exceptionOrNull()?.message ?: return BiometricError.FAILED
        return try { BiometricError.valueOf(name) } catch (_: Exception) { BiometricError.FAILED }
    }

    /** Convenience to get availability + wording for settings UI. */
    fun currentAvailability(activity: Context): Availability = availability(activity)
}
