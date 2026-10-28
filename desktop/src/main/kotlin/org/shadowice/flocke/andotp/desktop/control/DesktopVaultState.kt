package org.shadowice.flocke.andotp.desktop.control

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.View.EntryListPresentation

import javax.crypto.SecretKey

/**
 * Headless, Swing-free state of an unlocked desktop vault. It owns the [SecretKey] needed for
 * saves, the shared [EntryList] and its [EntryListPresentation] (all sort/search/tag filtering
 * stays in `:core`), and exposes exactly the mutations the dialogs and menus trigger. The frame
 * only observes [presentation] through its table model, so every branch here is unit-testable
 * without a display.
 */
class DesktopVaultState(val key: SecretKey, private val entries: EntryList = EntryList()) {

    val entryList: EntryList get() = entries

    val presentation: EntryListPresentation = EntryListPresentation(entries).apply {
        showReorderedEntries()
    }

    /** Replaces the list contents after an unlock or restore and resets the projection. */
    fun load(loaded: ArrayList<Entry>) {
        // A restore discards every previously loaded entry; zeroize its owned seeds (Android
        // parity) before dropping the references.
        entries.getEntries().forEach { it.destroy() }
        entries.updateEntries(loaded, true)
        presentation.showReorderedEntries()
    }

    /** Adds a new entry; returns false when an equal entry already exists. */
    fun add(entry: Entry): Boolean {
        val added = entries.addEntry(entry)
        if (added) presentation.showReorderedEntries()
        return added
    }

    /** Replaces [existing] with [replacement], preserving the manual order position and list id. */
    fun replace(existing: Entry, replacement: Entry): Boolean {
        val index = entries.indexOf(existing)
        if (index < 0) return false
        val replaced = entries.replaceEntry(index, replacement)
        if (replaced) {
            // The old instance leaves the list for good; wipe the seed it still owns.
            existing.destroy()
            presentation.showReorderedEntries()
        }
        return replaced
    }

    /** Removes the entry at the source-list position and returns it for cleanup. */
    fun removeAt(position: Int): Entry? {
        if (position < 0 || position >= entries.getEntries().size) return null
        val removed = entries.getEntry(position)
        entries.removeEntry(position)
        removed.destroy()
        presentation.showReorderedEntries()
        return removed
    }

    fun snapshot(): ArrayList<Entry> = entries.getEntries()

    /** Runs the core set-search/search/publish sequence; a blank query restores the full list. */
    fun applySearch(query: String,
                    includes: List<Constants.SearchIncludes> = DEFAULT_INCLUDES) {
        presentation.setSearch(query, includes)
        presentation.publishSearch(presentation.search(query, includes))
    }

    /**
     * Filters the projection by tag selection. The core filter treats an empty selection as
     * "match nothing", so an empty filter (with noTags unset) restores the full projection instead.
     */
    fun applyTagFilter(selected: List<String>, noTags: Boolean,
                       functionality: Constants.TagFunctionality = Constants.TagFunctionality.OR) {
        if (selected.isEmpty() && !noTags) {
            presentation.showReorderedEntries()
        } else {
            // filterByTags already selects the tags and refreshes the projection.
            presentation.filterByTags(selected, noTags, functionality)
        }
    }

    /**
     * Recomputes cached OTP values for time-based entries. Returns how many entries now hold a
     * token; the Swing layer calls [EntryTableModel.refresh] afterwards on the dispatch thread.
     */
    fun tickTokens(): Int {
        var updated = 0
        for (entry in entries.getEntries()) {
            if (entry.updateOTP(false) && entry.getCurrentOTP() != null) updated++
        }
        return updated
    }

    companion object {
        val DEFAULT_INCLUDES: List<Constants.SearchIncludes> = listOf(
            Constants.SearchIncludes.LABEL,
            Constants.SearchIncludes.ISSUER,
            Constants.SearchIncludes.TAGS
        )
    }
}
