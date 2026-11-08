package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.KeyValueSettings
import com.fairuniversal.offlineotp.Utilities.PasswordVault
import com.fairuniversal.offlineotp.Utilities.VaultReadError
import com.fairuniversal.offlineotp.Utilities.VaultSettingsKeys
import com.fairuniversal.offlineotp.Utilities.VaultWriteError
import com.fairuniversal.offlineotp.Utilities.VaultWriteResult

import java.util.ArrayList
import javax.crypto.SecretKey

/**
 * Headless application controller over the portable [PasswordVault]. The Swing shell only sees
 * [Session] outcomes and never touches crypto or storage types directly, which keeps every branch
 * testable without a display.
 */
class VaultController(private val vault: PasswordVault) {

    /** UI-facing failure reasons; the wording lives in the Swing layer. */
    enum class Failure { MISSING, AUTHENTICATION_FAILED, CORRUPT, IO_FAILURE, ALREADY_EXISTS, INVALID_INPUT }

    sealed class Session {
        data class Loaded(val entries: ArrayList<Entry>, val key: SecretKey) : Session()
        data class Error(val failure: Failure) : Session()
    }

    fun isInitialized(): Boolean = vault.isInitialized()

    /**
     * The portable preference store beside the vault, exposed for the preferences export/import.
     * It is available whether or not the vault is unlocked: these are app settings, not secrets.
     */
    val preferences: KeyValueSettings get() = vault.preferences

    /** Provisions a new empty vault and immediately unlocks it so the window can render. */
    fun createVault(password: String): Session = when (val result = vault.create(password)) {
        is VaultWriteResult.Saved -> openVault(password)
        is VaultWriteResult.Rejected -> Session.Error(mapWrite(result.error))
        is VaultWriteResult.RecoveryRequired -> Session.Error(Failure.IO_FAILURE)
    }

    fun openVault(password: String): Session = when (val outcome = vault.open(password)) {
        is PasswordVault.Open.Unlocked -> Session.Loaded(outcome.entries, outcome.key)
        is PasswordVault.Open.Failed -> Session.Error(mapRead(outcome.error))
    }

    /**
     * Re-keys the vault from [currentPassword] to [newPassword] using fresh Argon2id parameters
     * and returns the preserved entries + new session key so an open session stays valid.
     */
    fun changePassword(currentPassword: String, newPassword: String): Session =
        when (val outcome = vault.changePassword(currentPassword, newPassword)) {
            is PasswordVault.ReKey.Changed -> Session.Loaded(outcome.entries, outcome.key)
            is PasswordVault.ReKey.Failed -> Session.Error(mapWrite(outcome.error))
        }

    /**
     * Opens the vault from a hardware-derived key (FIDO2 PRF envelope). The key never touches the
     * settings verifier; GCM authentication is the access check, exactly as on Android's
     * hardware-unlock path.
     */
    fun unlockWithKey(key: SecretKey): Session = when (val outcome = vault.openWithKey(key)) {
        is PasswordVault.Open.Unlocked -> Session.Loaded(outcome.entries, outcome.key)
        is PasswordVault.Open.Failed -> Session.Error(mapRead(outcome.error))
    }

    fun save(key: SecretKey, entries: ArrayList<Entry>): Boolean =
        vault.save(key, entries) is VaultWriteResult.Saved

    /**
     * The list order chosen last time, mirroring Android's persisted sort setting. A missing or
     * unreadable value falls back to the vault order, never to an exception.
     */
    fun savedSortMode(): Constants.SortMode = try {
        Constants.SortMode.valueOf(vault.preferences.getString(SORT_MODE_KEY, Constants.SortMode.UNSORTED.name))
    } catch (_: RuntimeException) {
        Constants.SortMode.UNSORTED
    }

    /** Remembers the list order beside the vault; a write failure only loses the preference. */
    fun saveSortMode(mode: Constants.SortMode) {
        try {
            vault.preferences.putString(SORT_MODE_KEY, mode.name)
        } catch (_: Exception) {
            // The order still applies to the open session.
        }
    }

    /** Configured idle auto-lock timeout in seconds (`0` disables auto-lock). */
    fun savedInactivityLockSeconds(): Int = try {
        vault.preferences.getInt(VaultSettingsKeys.UI_INACTIVITY_LOCK_SECONDS, 0).coerceAtLeast(0)
    } catch (_: RuntimeException) {
        0
    }

    /** Persists the idle auto-lock timeout (`0` or negative removes the preference). */
    fun saveInactivityLockSeconds(seconds: Int) {
        trySaveInactivityLockSeconds(seconds.coerceAtLeast(0))
    }

    /** Reports failure so the Compose settings form does not claim an unsaved change. */
    fun trySaveInactivityLockSeconds(seconds: Int): Boolean {
        if (seconds < 0) return false
        return try {
            if (seconds == 0) vault.preferences.remove(VaultSettingsKeys.UI_INACTIVITY_LOCK_SECONDS)
            else vault.preferences.putInt(VaultSettingsKeys.UI_INACTIVITY_LOCK_SECONDS, seconds)
            true
        } catch (_: Exception) { false }
    }

    fun backup(): Boolean = vault.backup()

    private companion object {
        /** Key used in the client settings file; never part of the encrypted vault document. */
        const val SORT_MODE_KEY = "ui_sort_mode"
    }

    private fun mapRead(error: VaultReadError): Failure = when (error) {
        VaultReadError.MISSING, VaultReadError.MISSING_KEY -> Failure.MISSING
        VaultReadError.AUTHENTICATION_FAILED -> Failure.AUTHENTICATION_FAILED
        VaultReadError.CORRUPT, VaultReadError.RECOVERY_REQUIRED -> Failure.CORRUPT
        VaultReadError.IO_FAILURE -> Failure.IO_FAILURE
    }

    private fun mapWrite(error: VaultWriteError): Failure = when (error) {
        VaultWriteError.MISSING, VaultWriteError.MISSING_KEY -> Failure.MISSING
        VaultWriteError.AUTHENTICATION_FAILED -> Failure.AUTHENTICATION_FAILED
        VaultWriteError.CORRUPT -> Failure.CORRUPT
        VaultWriteError.IO_FAILURE, VaultWriteError.BACKUP_FAILED -> Failure.IO_FAILURE
        VaultWriteError.ALREADY_EXISTS -> Failure.ALREADY_EXISTS
        VaultWriteError.INVALID_INPUT -> Failure.INVALID_INPUT
        VaultWriteError.CANCELED -> Failure.INVALID_INPUT
    }
}
