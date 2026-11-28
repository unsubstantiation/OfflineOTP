package com.fairuniversal.offlineotp.Tasks

import android.content.Context
import android.util.Base64
import android.util.Log

import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.EncryptionHelper
import com.fairuniversal.offlineotp.Utilities.EncryptionHelper.PBKDF2Credentials
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.Utilities.LoginBackupKey
import com.fairuniversal.offlineotp.Utilities.SessionState
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.Utilities.VaultSession

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.spec.InvalidKeySpecException

import java.util.Arrays

/** Verifies a password/PIN, returning a generation-bound derived key on success. */
open class AuthenticationTask(context: Context, private val isAuthUpgrade: Boolean,
                              private val existingAuthCredentials: String, plainPassword: String?) :
        UiBasedBackgroundTask<AuthenticationOutcome>(AuthenticationOutcome.Rejected(AuthenticationError.DENIED)) {
    @Volatile private var plainPassword: String? = plainPassword
    protected override fun onCancellation() { plainPassword = null }
    private val applicationContext = context.getApplicationContext()
    private val settings: Settings = Settings(applicationContext)

    protected override fun discardResult(result: AuthenticationOutcome) {
        if (result is AuthenticationOutcome.Verified) result.close()
    }

    protected override fun doInBackground(): AuthenticationOutcome {
        val password = plainPassword.also { plainPassword = null }
        if (isCanceled() || password == null || password.length > InputPolicy.MAX_TEXT_CHARS) return AuthenticationOutcome.Rejected(AuthenticationError.DENIED)
        // A copied portable file is its own authority, not the destination's stale verifier.
        if (com.fairuniversal.offlineotp.Utilities.DatabaseHelper.hasPortableVault(applicationContext)) {
            return try {
                val key = com.fairuniversal.offlineotp.Utilities.DatabaseHelper.portableRecoveryKey(applicationContext, password)
                val raw = key.encoded
                try {
                    if (isCanceled()) AuthenticationOutcome.Rejected(AuthenticationError.DENIED)
                    else {
                        offerLoginBackupKey(password)
                        AuthenticationOutcome.verified(raw, taskGeneration())
                    }
                } finally { raw.fill(0) }
            } catch (_: Exception) { AuthenticationOutcome.Rejected(AuthenticationError.DENIED) }
        }
        return if (isAuthUpgrade) {
            upgradeAuthentication(password)
        } else {
            confirmAuthentication(password)
        }
    }

    /** Derive a separate, session-only backup key after a real password unlock, never for a PIN. */
    private fun offerLoginBackupKey(password: String) {
        if (isCanceled() || settings.getAuthMethod() != Constants.AuthMethod.PASSWORD ||
                VaultSession.snapshot() !is SessionState.Unlocking) return
        try {
            LoginBackupKey.derive(password).use { candidate ->
                if (!isCanceled()) VaultSession.offerBackupKey(candidate, taskGeneration())
            }
        } catch (_: Exception) {
            // Authentication can still succeed. Automatic backup remains unavailable until a
            // password unlock successfully derives its key; never fall back to a weaker key.
        }
    }

    /** Verifies legacy SHA-256 credentials and attempts a PBKDF2 upgrade. */
    private fun upgradeAuthentication(password: String): AuthenticationOutcome {
        val hashedPassword = String(Hex.encodeHex(DigestUtils.sha256(password)))
        if (hashedPassword != existingAuthCredentials)
            return AuthenticationOutcome.Rejected(AuthenticationError.DENIED)

        val key = settings.setAuthCredentials(password) {
            !isCanceled()
                    && settings.getAuthCredentials().isEmpty()
                    && existingAuthCredentials == settings.getOldCredentials(settings.getAuthMethod())
        }

        if (key == null)
            return AuthenticationOutcome.Rejected(AuthenticationError.LEGACY_MIGRATION_FAILED)
        else {
            try {
                offerLoginBackupKey(password)
                return AuthenticationOutcome.verified(key, taskGeneration())
            } finally {
                Arrays.fill(key, 0.toByte())
            }
        }
    }

    /** Checks the entered password/PIN against the stored Argon2id (or legacy PBKDF2) credentials. */
    private fun confirmAuthentication(password: String): AuthenticationOutcome {
        var credentials: PBKDF2Credentials? = null
        var passwordArray: ByteArray? = null
        try {
            val derived = settings.deriveVaultCredentials(password)
            credentials = derived
            passwordArray = Base64.decode(existingAuthCredentials, Base64.URL_SAFE)
            if (MessageDigest.isEqual(passwordArray, derived.password)) {
                offerLoginBackupKey(password)
                return AuthenticationOutcome.verified(derived.key, taskGeneration())
            }
            return AuthenticationOutcome.Rejected(AuthenticationError.DENIED)
        } catch (error: NoSuchAlgorithmException) {
            Log.e("AuthenticationTask", "Problem decoding password")
            return AuthenticationOutcome.Rejected(AuthenticationError.INVALID_CONFIGURATION)
        } catch (error: InvalidKeySpecException) {
            Log.e("AuthenticationTask", "Problem decoding password")
            return AuthenticationOutcome.Rejected(AuthenticationError.INVALID_CONFIGURATION)
        } catch (error: IllegalArgumentException) {
            Log.e("AuthenticationTask", "Problem decoding password")
            return AuthenticationOutcome.Rejected(AuthenticationError.INVALID_CONFIGURATION)
        } catch (error: IllegalStateException) {
            Log.e("AuthenticationTask", "Problem decoding password")
            return AuthenticationOutcome.Rejected(AuthenticationError.INVALID_CONFIGURATION)
        } finally {
            credentials?.let {
                Arrays.fill(it.password, 0.toByte())
                Arrays.fill(it.key, 0.toByte())
            }
            passwordArray?.let { Arrays.fill(it, 0.toByte()) }
        }
    }
}
