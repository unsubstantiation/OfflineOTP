package com.fairuniversal.offlineotp.View

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryList

/**
 * Borrowed identity for a delayed action, not an authorization lease or an entry owner.
 * Not a data class: do not generate entry-based equality, copy, destructuring or diagnostics.
 */
class EntryActionTarget(entry: Entry, private val generation: Long) {
    private val expected: Entry = entry
    private val id: Long = entry.getListId()

    /** Caller must also verify the live session key under the mutation lock before acting. */
    fun resolve(source: EntryList, currentGeneration: Long): Entry? {
        if (generation != currentGeneration) return null
        for (candidate in source.getEntries()) {
            if (candidate === expected && candidate.getListId() == id) return candidate
        }
        return null
    }

    override fun toString(): String = "EntryActionTarget(redacted)"
}
