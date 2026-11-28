package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.Database.Entry

typealias VaultReadError = com.fairuniversal.offlineotp.domain.PersistenceReadFailure

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

typealias VaultWriteError = com.fairuniversal.offlineotp.domain.PersistenceWriteFailure
enum class VaultCommitDecision { COMMITTED, CANCELED, UNKNOWN;
    fun portable(): com.fairuniversal.offlineotp.domain.PersistenceCommit =
        com.fairuniversal.offlineotp.domain.PersistenceCommit.valueOf(name)
    companion object {
        @JvmStatic fun fromCommit(success: Boolean): VaultCommitDecision = if (success) COMMITTED else UNKNOWN
    }
}
typealias VaultCommitState = com.fairuniversal.offlineotp.domain.PersistenceCommitState

/** A staged migration is not a boolean: metadata may be committed even if promotion failed. */
sealed class VaultWriteResult {
    object Saved : VaultWriteResult()
    data class Rejected(val error: VaultWriteError) : VaultWriteResult()
    data class RecoveryRequired(val commitState: VaultCommitState) : VaultWriteResult()
}
