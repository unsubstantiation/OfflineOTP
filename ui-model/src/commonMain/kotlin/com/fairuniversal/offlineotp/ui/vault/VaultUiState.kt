package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.View.EntryListPresentation

/**
 * How a search box and a tag selection combine when both are active.
 *
 * The two existing clients disagree deliberately: Android's search temporarily overrides the tag
 * filter, while the desktop list keeps both — and the README documents that difference. The shared
 * projection has to be able to express either, so a host's choice stays a value instead of a
 * behaviour buried in one adapter.
 */
enum class VaultFilterCombination {
    /** A non-empty search replaces the tag selection (Android). */
    SEARCH_OVERRIDES_TAGS,

    /** Rows must match the search *and* the tag selection (desktop). */
    SEARCH_AND_TAGS
}

/**
 * The selection a vault screen shows: search text, tag selection, order and tab.
 *
 * This is display state only. It carries no session authority, so holding one of these after a
 * lock discloses nothing; the host must still refuse to publish cards the live session no longer
 * authorizes.
 */
data class VaultFilter(
    val query: String = "",
    val searchIncludes: List<Constants.SearchIncludes> = DEFAULT_SEARCH_INCLUDES,
    val sortMode: Constants.SortMode = Constants.SortMode.UNSORTED,
    /** Selected tag names; combined by [tagFunctionality]. */
    val selectedTags: List<String> = emptyList(),
    /** Include entries with no tags in the tag filter. */
    val untaggedSelected: Boolean = false,
    val tagFunctionality: Constants.TagFunctionality = Constants.TagFunctionality.OR,
    /** Selected tab, or null for the whole vault. */
    val category: EntryListPresentation.Category? = null,
    /** How a search and a tag selection narrow the list together. */
    val combination: VaultFilterCombination = VaultFilterCombination.SEARCH_OVERRIDES_TAGS
) {
    /** True when a tag or untagged selection narrows the list. */
    val hasTagFilter: Boolean get() = selectedTags.isNotEmpty() || untaggedSelected

    /** True when the search box narrows the list. */
    val hasQuery: Boolean get() = query.isNotBlank()

    /** The filter after the search box reports [next]. */
    fun withQuery(next: String): VaultFilter = copy(query = next)

    /** The filter after a tab is chosen; `null` means the whole vault. */
    fun withCategory(next: EntryListPresentation.Category?): VaultFilter = copy(category = next)

    fun withSortMode(next: Constants.SortMode): VaultFilter = copy(sortMode = next)

    /**
     * The filter after one tag chip is tapped.
     *
     * `SINGLE` replaces the selection instead of adding to it, which is what the `SINGLE` tag
     * functionality means in `:core` (one tag at a time); `OR` and `AND` both accumulate, and the
     * matcher in `EntryList` applies the difference. Tapping the selected chip again removes it, so a
     * chip never becomes a one-way switch. The untagged pseudo chip is a separate callback
     * ([withUntaggedToggled]) and is never touched here.
     */
    fun withTagToggled(name: String): VaultFilter {
        val selected = when (tagFunctionality) {
            Constants.TagFunctionality.SINGLE ->
                if (name in selectedTags) emptyList() else listOf(name)
            else ->
                if (name in selectedTags) selectedTags - name else selectedTags + name
        }
        return copy(selectedTags = selected)
    }

    /** The filter after the untagged pseudo chip is tapped. */
    fun withUntaggedToggled(): VaultFilter = copy(untaggedSelected = !untaggedSelected)

    /**
     * The filter with the narrowing removed: query, tag selection and untagged cleared. The tab and
     * the order stay, because "Clear" clears the filters that hide rows, not the user's tab choice.
     */
    fun clearedFilters(): VaultFilter =
        copy(query = "", selectedTags = emptyList(), untaggedSelected = false)

    companion object {
        /** Both clients search label, issuer and tags unless the user narrows it. */
        val DEFAULT_SEARCH_INCLUDES: List<Constants.SearchIncludes> = listOf(
            Constants.SearchIncludes.LABEL,
            Constants.SearchIncludes.ISSUER,
            Constants.SearchIncludes.TAGS
        )
    }
}

/** One tab in the category strip. */
data class VaultCategoryTab(
    val category: EntryListPresentation.Category,
    val count: Int,
    val selected: Boolean
)

/**
 * One tag chip in the tag filter panel. [isUntagged] marks the pseudo chip that selects entries
 * with no tags; it is a flag, not a name comparison, so a real tag literally called "Untagged"
 * stays a normal tag.
 */
data class VaultTagChip(
    val name: String,
    val count: Int,
    val selected: Boolean,
    val isUntagged: Boolean = false
)

/** One list order, for the shared sort menu. */
data class VaultSortOption(
    val mode: Constants.SortMode,
    val selected: Boolean
)

/**
 * Per-host display settings the projection needs. Android and desktop read their own settings
 * stores and hand the values over, so the shared card logic never reaches for either one.
 */
data class VaultAppearance(
    /** Show issuer artwork; hosts without the asset pack still render a letter tile. */
    val showThumbnails: Boolean = true,
    /** Android may retain its existing toolbar, tabs and tag drawer during migration. */
    val showControls: Boolean = true,
    /** Long-label behaviour, from the user's settings. */
    val labelDisplay: Constants.LabelDisplay = Constants.LabelDisplay.TRUNCATE,
    /** Group a six-digit code into halves. */
    val groupTokens: Boolean = true,
    /** Null keeps the desktop half-split convention; a value uses Android right-aligned groups. */
    val tokenGroupSize: Int? = null,
    val showIssuer: Boolean = true,
    val showPreviousToken: Boolean = true,
    /** Non-default token steps always keep a countdown even when the standard-step one is off. */
    val showIndividualCountdowns: Boolean = true,
    val hideCountdownWhenMasked: Boolean = false,
    /**
     * How many seconds before rollover a code is flagged as expiring. 8 matches the Android card's
     * `Entry.EXPIRY_TIME` and the Swing client's token colour change.
     */
    val expiringSeconds: Int = 8
)

/**
 * One immutable frame of the vault screen.
 *
 * Everything the composables need is here, so a host only has to rebuild this value when its
 * entries, filter or clock step changes. [cards] borrow nothing: they are plain strings computed
 * for this frame, which is what lets a Compose recomposition drop a locked frame safely.
 */
data class VaultUiState(
    val filter: VaultFilter = VaultFilter(),
    val categories: List<VaultCategoryTab> = emptyList(),
    val tags: List<VaultTagChip> = emptyList(),
    val sortOptions: List<VaultSortOption> = emptyList(),
    val cards: List<VaultCardState> = emptyList(),
    val emptyTitle: String = "",
    val emptyDescription: String = "",
    /** Total entries in the vault, used for the header count. */
    val totalEntries: Int = 0
) {
    val isEmpty: Boolean get() = cards.isEmpty()

    // Card ids and tags are not secrets, but keep the diagnostics scoped to the shape of the frame.
    override fun toString(): String =
        "VaultUiState(cards=${cards.size}, categories=${categories.size}, " +
            "tags=${tags.size}, filtered=${filter.hasTagFilter || filter.hasQuery})"
}
