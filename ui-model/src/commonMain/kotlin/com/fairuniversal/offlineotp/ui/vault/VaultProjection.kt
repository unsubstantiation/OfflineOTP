package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryList
import com.fairuniversal.offlineotp.Database.EntryTagFilter
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.View.EntryListPresentation

/**
 * Turns the shared display projection into one immutable [VaultUiState].
 *
 * Both clients already agree on *which* entries a filter selects: that logic lives in
 * `EntryList`/`EntryListPresentation` and stays there. What used to be duplicated per client was
 * everything after that — grouping a code, counting seconds, choosing the empty message, deciding
 * when a note may be printed — and that is what this class centralizes.
 *
 * The per-row rules themselves live in [VaultCards], so a host that paints one row at a time asks
 * for one card and gets the same answer this class would have put in a frame.
 *
 * The projection is read-only with respect to ownership: it borrows entries, refreshes their cached
 * time token in place exactly like the existing cards do, and never retains an [Entry] in the state
 * it returns. A frame produced from a locked panel therefore contains strings only.
 */
class VaultProjection(
    private val entries: EntryList,
    private val presentation: EntryListPresentation
) {

    /**
     * Points the shared presentation at [filter]: tab, order, search and tag selection, in the one
     * order that is correct (`setCategory`/`setSortMode`, then the search, then the tag refresh —
     * a tag refresh re-runs the search when a query is active, exactly as `EntryList` defines it).
     *
     * Hosts with a synchronous filter call this and then [snapshot]. A host whose search is
     * asynchronous (Android runs it off the main thread and re-checks the presentation revision)
     * keeps that path and calls [snapshot] only, so this never overrides a revision check.
     */
    fun applyFilter(filter: VaultFilter): VaultProjection {
        presentation.setCategory(filter.category)
        presentation.setSortMode(filter.sortMode)
        presentation.setSearch(filter.query, filter.searchIncludes)
        when (filter.combination) {
            // Android's rule: a live search replaces the tag selection (`refreshTags` prefers the
            // query whenever one is set), so the core projection already does the right thing.
            VaultFilterCombination.SEARCH_OVERRIDES_TAGS ->
                presentation.filterByTags(
                    filter.selectedTags, filter.untaggedSelected, filter.tagFunctionality
                )
            // The desktop rule: search and tags both narrow the list, so the tag test is applied to
            // the search result before it is published. Same rule and same core matcher the desktop
            // list used, now in one place.
            VaultFilterCombination.SEARCH_AND_TAGS -> {
                val result = presentation.search(filter.query, filter.searchIncludes)
                if (filter.hasTagFilter) {
                    result.entries?.removeAll { entry ->
                        !EntryTagFilter.matches(
                            entry, filter.selectedTags, filter.untaggedSelected, filter.tagFunctionality
                        )
                    }
                }
                presentation.publishSearch(result)
            }
        }
        return this
    }

    /** [applyFilter] followed by [snapshot]: one frame for a host that owns no async search. */
    fun refresh(
        filter: VaultFilter,
        nowMillis: Long,
        revealedIds: Set<Long> = emptySet(),
        appearance: VaultAppearance = VaultAppearance(),
        strings: VaultStrings = VaultStrings.English
    ): VaultUiState = applyFilter(filter).snapshot(filter, nowMillis, revealedIds, appearance, strings)

    /**
     * Builds the frame for [filter] at [nowMillis].
     *
     * [revealedIds] holds the stable ids whose protected content is on screen; the frame contains
     * that content only for those ids. The caller must clear the set when the session locks.
     */
    fun snapshot(
        filter: VaultFilter,
        nowMillis: Long,
        revealedIds: Set<Long> = emptySet(),
        appearance: VaultAppearance = VaultAppearance(),
        strings: VaultStrings = VaultStrings.English
    ): VaultUiState {
        val nowSeconds = nowMillis / 1000L
        val cards = ArrayList<VaultCardState>(presentation.size())
        for (index in 0 until presentation.size()) {
            val entry = presentation.get(index)
            cards.add(card(entry, nowSeconds, entry.getListId() in revealedIds, appearance))
        }
        val all = entries.getEntries()
        val empty = emptyMessages(all.size, filter, strings)
        return VaultUiState(
            filter = filter,
            categories = EntryListPresentation.Category.entries.map { category ->
                VaultCategoryTab(
                    category = category,
                    count = all.count { EntryListPresentation.categoryOf(it) == category },
                    selected = category == filter.category
                )
            },
            tags = tagChips(all, filter, strings),
            sortOptions = Constants.SortMode.entries
                .map { VaultSortOption(it, it == filter.sortMode) },
            cards = cards,
            emptyTitle = empty.first,
            emptyDescription = empty.second,
            totalEntries = all.size
        )
    }

    /**
     * Builds one row. [nowSeconds] is sampled once per frame by [snapshot].
     *
     * The row itself is [VaultCards.of]; the frame is the one host that also owns the tick, so it
     * refreshes the cached code here (same reuse rule as the Android card and the Swing table:
     * recompute only when the step changed) before asking for the card. A token failure must not
     * take the whole list down with it, hence the `runCatching`.
     */
    private fun card(entry: Entry, nowSeconds: Long, revealed: Boolean, appearance: VaultAppearance): VaultCardState {
        if (VaultCards.hasCode(entry)) runCatching { entry.updateOTPAt(nowSeconds, false) }
        return VaultCards.of(entry, nowSeconds * 1000L, revealed, appearance)
    }

    /** Tag chips: one per tag in use, in the shared tag order, plus the untagged pseudo chip. */
    private fun tagChips(entries: List<Entry>, filter: VaultFilter, strings: VaultStrings): List<VaultTagChip> {
        val counts = this.entries.getTagCounts()
        val chips = ArrayList<VaultTagChip>(counts.size + 1)
        for (tag in this.entries.getSortedTags()) {
            chips.add(VaultTagChip(tag, counts[tag] ?: 0, tag in filter.selectedTags))
        }
        val untagged = entries.count { it.getTags().isEmpty() }
        if (untagged > 0 || filter.untaggedSelected) {
            chips.add(
                VaultTagChip(
                    name = strings.tagChipUntagged,
                    count = untagged,
                    selected = filter.untaggedSelected,
                    isUntagged = true
                )
            )
        }
        return chips
    }

    /**
     * Which empty panel to show, if any. A filtered list with no rows must not read as an empty
     * vault: the two situations need different advice.
     */
    private fun emptyMessages(total: Int, filter: VaultFilter, strings: VaultStrings): Pair<String, String> = when {
        total == 0 -> strings.emptyTitle to strings.emptyDescription
        filter.hasQuery || filter.hasTagFilter -> strings.noMatchesTitle to strings.noMatchesDescription
        filter.category != null -> strings.emptyTabTitle to strings.emptyTabDescription
        else -> strings.emptyTitle to strings.emptyDescription
    }
}
