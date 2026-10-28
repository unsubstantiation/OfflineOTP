package org.shadowice.flocke.andotp.desktop.control

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.PasswordVault
import org.shadowice.flocke.andotp.Utilities.VaultReadError
import org.shadowice.flocke.andotp.Utilities.VaultWriteError
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult

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

    fun save(key: SecretKey, entries: ArrayList<Entry>): Boolean =
        vault.save(key, entries) is VaultWriteResult.Saved

    fun backup(): Boolean = vault.backup()

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
