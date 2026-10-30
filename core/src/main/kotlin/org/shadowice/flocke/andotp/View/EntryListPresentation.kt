package org.shadowice.flocke.andotp.View

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Utilities.Constants

import java.util.ArrayList
import java.util.HashMap

/**
 * Display-only projection over a borrowed EntryList. No Context, views, session authority or I/O.
 * Entries are borrowed, not copied/owned: clearing this projection must never destroy them.
 * Search covers the selected category (or the whole vault without a category); when empty, tags apply.
 */
class EntryListPresentation(private val source: EntryList) {
    enum class Category { OTP, NOTES, SSH_KEYS, PASSWORDS, EMAILS }
    private var category: Category? = null

    private fun matches(entry: Entry, selected: Category?): Boolean = when (selected) {
        null -> true
        Category.OTP -> entry.isTimeBased() || entry.isCounterBased()
        Category.NOTES -> entry.isNote()
        Category.SSH_KEYS -> entry.isSshKey()
        Category.PASSWORDS -> entry.isPassword() || entry.isPasswordArchive()
        Category.EMAILS -> entry.isEmail()
    }

    /** Invalidates in-flight searches; caller then refreshes its current tag/search projection. */
    @Synchronized fun setCategory(value: Category?) { category = value; revision++ }

    private var displayed: ArrayList<Entry> = ArrayList()
    private var tags: MutableList<String> = ArrayList()
    private var sortMode: Constants.SortMode = Constants.SortMode.UNSORTED
    private var searchQuery: String = ""
    private var searchIncludes: List<Constants.SearchIncludes> = emptyList()
    private var revision: Long = 0

    @Synchronized fun size(): Int = displayed.size
    @Synchronized fun get(position: Int): Entry = displayed[position]
    @Synchronized fun indexOf(entry: Entry): Int = displayed.indexOf(entry)
    @Synchronized fun sourceIndex(position: Int): Int = source.indexOf(displayed[position])
    @Synchronized fun getSortMode(): Constants.SortMode = sortMode

    @Synchronized fun setSortMode(mode: Constants.SortMode) {
        sortMode = mode
        revision++
    }

    /** Preserve existing selections, remove unused tags, and select newly introduced tags. */
    fun reconcileTags(known: List<String>, active: List<String>): HashMap<String, Boolean> {
        val inUse = source.getAllTags()
        val selection = HashMap<String, Boolean>()
        for (tag in known) if (inUse.contains(tag)) selection[tag] = false
        for (tag in active) if (inUse.contains(tag)) selection[tag] = true
        for (tag in inUse) if (!selection.containsKey(tag)) selection[tag] = true
        return selection
    }

    @Synchronized fun selectTags(selected: List<String>) {
        tags = ArrayList(selected)
        revision++
    }

    @Synchronized fun filterByTags(selected: List<String>, noTags: Boolean,
                                  functionality: Constants.TagFunctionality) {
        selectTags(selected)
        refreshTags(noTags, functionality)
    }

    /** Invalidates in-flight results before a new asynchronous search is scheduled. */
    @Synchronized fun setSearch(query: String, includes: List<Constants.SearchIncludes>) {
        searchQuery = query
        searchIncludes = includes.distinct().sortedBy { it.ordinal }
        revision++
    }

    /** Rebuilds the active projection after entry, tag, or sort changes. */
    @Synchronized fun refreshTags(noTags: Boolean, functionality: Constants.TagFunctionality) {
        val candidates = if (searchQuery.isEmpty())
            source.getEntriesFilteredByTags(tags, noTags, functionality, sortMode)
        else
            source.getFilteredEntries(searchQuery, searchIncludes, sortMode)
        displayed = ArrayList(candidates.filter { matches(it, category) })
        revision++
    }

    /** Re-sort the current subset, not the complete vault, after usage changes. */
    @Synchronized fun resortAfterUsage(): Boolean {
        if (sortMode != Constants.SortMode.LAST_USED && sortMode != Constants.SortMode.MOST_USED)
            return false
        displayed = EntryList.sortEntries(displayed, sortMode)
        revision++
        return true
    }

    @Synchronized fun canDrag(): Boolean {
        return sortMode == Constants.SortMode.UNSORTED && searchQuery.isEmpty() &&
            source.getEntries().filter { matches(it, category) } == displayed
    }

    @Synchronized fun showReorderedEntries() {
        displayed = ArrayList(source.getEntries().filter { matches(it, category) })
        revision++
    }

    @Synchronized fun removeDisplayed(position: Int) {
        displayed.removeAt(position)
        revision++
    }

    @Synchronized fun invalidateSearch() { revision++ }

    @Synchronized fun clear() {
        displayed.clear()
        tags.clear()
        searchQuery = ""
        searchIncludes = emptyList()
        revision++
    }

    /** Computes a borrowed projection; callers still enforce session/generation at publication. */
    fun search(constraint: CharSequence?, includes: List<Constants.SearchIncludes>): SearchResult {
        val version: Long
        val mode: Constants.SortMode
        val selected: Category?
        synchronized(this) {
            version = revision
            mode = sortMode
            selected = category
        }
        // Do not hold the presentation monitor during collection work.
        val query = constraint?.toString().orEmpty()
        val fields = includes.distinct().sortedBy { it.ordinal }
        val matches = ArrayList(source.getFilteredEntries(query, fields, mode).filter { matches(it, selected) })
        return SearchResult(this, version, matches, query, fields)
    }

    @Synchronized fun publishSearch(result: SearchResult): Boolean {
        synchronized(result) {
            if (result.owner !== this || result.revision != revision || result.entries == null ||
                    result.query != searchQuery ||
                    (searchQuery.isNotEmpty() && result.includes != searchIncludes)) {
                result.close()
                return false
            }
            displayed = result.entries!!
            result.entries = null
            return true
        }
    }

    /** Owns only a list of borrowed references, never the entries/seeds themselves. */
    class SearchResult @JvmOverloads constructor(
        val owner: EntryListPresentation,
        val revision: Long,
        var entries: ArrayList<Entry>?,
        val query: String = "",
        val includes: List<Constants.SearchIncludes> = emptyList()
    ) : AutoCloseable {
        @Synchronized fun size(): Int = entries?.size ?: 0
        @Synchronized override fun close() { entries?.clear(); entries = null }
        override fun toString(): String = "EntrySearchResult(redacted)"
    }
}
