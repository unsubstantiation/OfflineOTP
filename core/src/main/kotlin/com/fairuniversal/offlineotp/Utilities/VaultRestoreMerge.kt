package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.domain.RestoreMergePolicy

/**
 * How a restored backup is combined with the entries already in the vault — the rule behind both
 * clients' **Replace existing entries** switch.
 *
 * Android's restore screen and the desktop restore dialogs ask the same question with the same
 * default (merge), so the combination itself lives here: the imported entries come first, an entry
 * the vault already holds is not duplicated and keeps its counters and metadata, and a replace
 * ignores the current entries entirely. Having one implementation is what keeps a phone restore and
 * a desktop restore of the same file from producing different vaults.
 */
object VaultRestoreMerge {

    /**
     * Builds the list to save.
     *
     * [imported] is read and *modified*: entries that duplicate one already in [current] are removed
     * from it and destroyed, so the caller's cleanup loop never destroys an entry the vault keeps.
     * [current] is borrowed, never modified or destroyed — in merge mode those instances end up in
     * the result exactly as they are.
     *
     * @return the entries to write, duplicates already removed.
     * @throws IllegalArgumentException when the result would still hold two equal entries, which
     *   `EntryList` would collapse silently on the next save.
     */
    @JvmStatic
    fun plan(imported: MutableList<Entry>, current: List<Entry>, replace: Boolean): ArrayList<Entry> {
        val retained = HashSet(current)
        val decision = RestoreMergePolicy().plan(imported.map { retained.contains(it) }, current.size,
            imported.toHashSet().size == imported.size, retained.size == current.size, replace)
        require(decision.failure == null) { "Invalid restore combination" }
        val keep = decision.importedIndices.toHashSet()
        val iterator = imported.iterator()
        var index = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!keep.contains(index)) { iterator.remove(); entry.destroy() }
            index++
        }
        val merged = ArrayList<Entry>(imported)
        if (decision.retainCurrent) merged.addAll(current)
        return merged
    }
}
