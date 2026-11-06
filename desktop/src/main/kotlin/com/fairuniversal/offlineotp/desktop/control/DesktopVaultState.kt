package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryList
import com.fairuniversal.offlineotp.Database.EntryTagFilter
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.View.EntryListPresentation

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
    private var query = ""
    private var searchIncludes = DEFAULT_INCLUDES
    private var selectedTags: List<String> = emptyList()
    private var includeUntagged = false
    private var tagMode = Constants.TagFunctionality.OR

    val presentation: EntryListPresentation = EntryListPresentation(entries).apply {
        showReorderedEntries()
    }

    /** Replaces the list contents after an unlock or restore and resets the projection. */
    fun load(loaded: ArrayList<Entry>) {
        // A restore discards every previously loaded entry; zeroize its owned seeds (Android
        // parity) before dropping the references.
        entries.getEntries().forEach { it.destroy() }
        entries.updateEntries(loaded, true)
        refreshProjection()
    }

    /**
     * Installs the list a restore produced. Android's restore screen offers a **Replace existing
     * entries** switch (off by default, i.e. merge), so [destroyPrevious] is only true for a
     * replace: in merge mode the entries already in the vault are part of [restored] and must keep
     * their secrets, while a replace zeroizes the entries the backup dropped.
     */
    fun installRestored(restored: ArrayList<Entry>, destroyPrevious: Boolean) {
        require(restored.toHashSet().size == restored.size) { "Duplicate entries in a restore" }
        val previous = ArrayList(entries.getEntries())
        while (entries.getEntries().isNotEmpty()) entries.removeEntry(entries.getEntries().size - 1)
        if (destroyPrevious) previous.forEach { it.destroy() }
        check(entries.addDistinctEntries(restored)) { "Duplicate entries in a restore" }
        refreshProjection()
    }

    /** Adds a new entry; returns false when an equal entry already exists. */
    fun add(entry: Entry): Boolean {
        val added = entries.addEntry(entry)
        if (added) refreshProjection()
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
            refreshProjection()
        }
        return replaced
    }

    /** Removes the entry at the source-list position and returns it for cleanup. */
    fun removeAt(position: Int): Entry? {
        if (position < 0 || position >= entries.getEntries().size) return null
        val removed = entries.getEntry(position)
        entries.removeEntry(position)
        removed.destroy()
        refreshProjection()
        return removed
    }

    fun snapshot(): ArrayList<Entry> = entries.getEntries()

    /**
     * Swaps two *displayed* rows in the manual order. Android allows this only through drag & drop
     * in the unsorted, unfiltered view; [EntryListPresentation.canDrag] is the shared rule, so both
     * clients reorder under exactly the same conditions.
     */
    fun move(fromDisplayed: Int, toDisplayed: Int): Boolean {
        if (!presentation.canDrag()) return false
        if (fromDisplayed !in 0 until presentation.size() || toDisplayed !in 0 until presentation.size())
            return false
        entries.swapEntries(presentation.sourceIndex(fromDisplayed), presentation.sourceIndex(toDisplayed))
        presentation.showReorderedEntries()
        return true
    }

    /** Search and tag filters compose within the selected category. */
    fun applySearch(query: String, includes: List<Constants.SearchIncludes> = DEFAULT_INCLUDES) {
        this.query = query
        this.searchIncludes = includes.toList()
        refreshProjection()
    }

    /**
     * Selects a tab and rebuilds the projection for it. An active search and tag filter keep
     * applying, exactly like Android's `setCategory()`, whose `refreshTags()` re-runs the stored
     * query against the new category (pinned by the sandbox check "the adapter keeps a live search
     * across tag and sort refreshes").
     */
    fun applyCategory(category: EntryListPresentation.Category) {
        presentation.setCategory(category)
        refreshProjection()
    }

    /** Empty selection without untagged means all entries; otherwise select tags and/or tagless. */
    fun applyTagFilter(selected: List<String>, noTags: Boolean,
                       functionality: Constants.TagFunctionality = Constants.TagFunctionality.OR) {
        selectedTags = selected.toList()
        includeUntagged = noTags
        tagMode = functionality
        refreshProjection()
    }

    /**
     * Orders the visible projection like Android's sort menu. The vault's own order is untouched,
     * so choosing the unsorted mode again restores it; the active search and tag filter survive.
     */
    fun applySortMode(mode: Constants.SortMode) {
        presentation.setSortMode(mode)
        refreshProjection()
    }

    /** Reapply both filters after a mutation or tab change without destroying borrowed entries. */
    fun refreshProjection() {
        presentation.setSearch(query, searchIncludes)
        val result = presentation.search(query, searchIncludes)
        if (selectedTags.isNotEmpty() || includeUntagged) {
            // Shared with Android's tag drawer, so the same selection keeps the same rows.
            result.entries?.removeAll { entry ->
                !EntryTagFilter.matches(entry, selectedTags, includeUntagged, tagMode)
            }
        }
        presentation.publishSearch(result)
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
