package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import org.shadowice.flocke.andotp.Database.Entry

import java.util.ArrayList
import java.util.function.BooleanSupplier
import javax.crypto.SecretKey

/**
 * Synchronous restore use case. Owns all decoded/loaded entries until it destroys them; no secret
 * payload escapes in the result. The screen owns consent, document access and rendering only.
 */
class VaultRestoreOperation(context: Context) {
    enum class Result {
        SAVED, EMPTY_IMPORT, INVALID_IMPORT, VAULT_UNAVAILABLE, SAVE_FAILED,
        SESSION_REVOKED, RECOVERY_REQUIRED, OPERATION_FAILED
    }

    private val persistence: EntryPersistence = EntryPersistence(context)

    private fun authorized(key: SecretKey?, generation: Long, permitted: BooleanSupplier): Boolean {
        return permitted.getAsBoolean() && VaultSession.isCurrent(generation)
                && key != null && VaultSession.getKey() === key
    }

    fun restore(text: String, replace: Boolean, key: SecretKey?, generation: Long,
                permitted: BooleanSupplier): Result {
        val imported = ArrayList<Entry>()
        val current = ArrayList<Entry>()
        try {
            if (!authorized(key, generation, permitted)) return Result.SESSION_REVOKED
            try {
                imported.addAll(DatabaseHelper.stringToEntries(text))
            } catch (invalid: RuntimeException) {
                return Result.INVALID_IMPORT
            }
            if (imported.isEmpty()) return Result.EMPTY_IMPORT
            // The read/merge/write is one mutation, not a stale read followed by a later save.
            synchronized (VaultSession.MutationLock) {
                if (!authorized(key, generation, permitted)) return Result.SESSION_REVOKED
                if (!replace) {
                    val read = persistence.load(key!!)
                    if (read is VaultReadResult.Unavailable) {
                        return if (read.error == VaultReadError.RECOVERY_REQUIRED)
                            Result.RECOVERY_REQUIRED else Result.VAULT_UNAVAILABLE
                    }
                    val loaded = (read as VaultReadResult.Loaded).takeEntries()
                    current.addAll(loaded)
                    val iterator = imported.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (current.contains(entry)) {
                            iterator.remove()
                            entry.destroy()
                        }
                    }
                }
                // Preserve historical ordering: new imports first, then existing entries. Duplicate
                // imports matching existing entries do not overwrite existing metadata/counters.
                val merged = ArrayList<Entry>(imported)
                merged.addAll(current)
                if (!authorized(key, generation, permitted)) return Result.SESSION_REVOKED
                val write = persistence.save(merged, key, false, null)
                return when (write) {
                    is VaultWriteResult.Saved -> Result.SAVED
                    is VaultWriteResult.RecoveryRequired -> Result.RECOVERY_REQUIRED
                    else -> Result.SAVE_FAILED
                }
            }
        } catch (error: RuntimeException) {
            // An unexpected policy/storage failure is not evidence of malformed import data.
            return Result.OPERATION_FAILED
        } finally {
            for (entry in imported) entry.destroy()
            for (entry in current) entry.destroy()
        }
    }
}
