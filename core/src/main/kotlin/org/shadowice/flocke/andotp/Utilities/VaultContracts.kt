package org.shadowice.flocke.andotp.Utilities

import org.shadowice.flocke.andotp.Database.Entry

enum class VaultReadError { MISSING, MISSING_KEY, AUTHENTICATION_FAILED, CORRUPT, IO_FAILURE, RECOVERY_REQUIRED }

/** A successful empty vault is Loaded(empty), never an error-shaped empty list. */
sealed class VaultReadResult {
    class Loaded(entries: List<Entry>) : VaultReadResult(), AutoCloseable {
        private var owned: ArrayList<Entry>? = ArrayList(entries)
        /** Transfers entry ownership exactly once; the caller must destroy entries when done. */
        @Synchronized fun takeEntries(): ArrayList<Entry> {
            val entries = checkNotNull(owned) { "Vault entries already transferred or closed" }
            owned = null
            return entries
        }
        @Synchronized override fun close() {
            owned?.forEach { it.destroy() }
            owned = null
        }
        override fun toString(): String = "VaultReadResult.Loaded(redacted)"
    }
    data class Unavailable(val error: VaultReadError) : VaultReadResult()
}

enum class VaultWriteError {
    MISSING, MISSING_KEY, AUTHENTICATION_FAILED, CORRUPT, IO_FAILURE,
    INVALID_INPUT, ALREADY_EXISTS, BACKUP_FAILED, CANCELED
}
enum class VaultCommitDecision { COMMITTED, CANCELED, UNKNOWN;
    companion object {
        @JvmStatic fun fromCommit(success: Boolean): VaultCommitDecision = if (success) COMMITTED else UNKNOWN
    }
}
enum class VaultCommitState { UNKNOWN, COMMITTED }

/** A staged migration is not a boolean: metadata may be committed even if promotion failed. */
sealed class VaultWriteResult {
    object Saved : VaultWriteResult()
    data class Rejected(val error: VaultWriteError) : VaultWriteResult()
    data class RecoveryRequired(val commitState: VaultCommitState) : VaultWriteResult()
}
