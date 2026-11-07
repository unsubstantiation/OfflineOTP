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

    /** Builds one row. [nowSeconds] is sampled once per frame by [snapshot]. */
    private fun card(entry: Entry, nowSeconds: Long, revealed: Boolean, appearance: VaultAppearance): VaultCardState {
        val kind = kindOf(entry)
        val hasCode = entry.isTimeBased() || entry.isCounterBased()
        if (hasCode) {
            // Same reuse rule as the Android card and the Swing table: recompute only when the step
            // changed, and never let a token failure take the whole list down with it.
            runCatching { entry.updateOTPAt(nowSeconds, false) }
        }
        val pinRequired = entry.getPin().isEmpty() && entry.getType() == Entry.OTPType.MOTP
        val rawToken = if (hasCode && !pinRequired) entry.getCurrentOTP().orEmpty() else ""
        val token = if (rawToken.isEmpty()) null else TokenDisplay.group(rawToken, appearance.groupTokens)
        // Only a time-based code has a step to count down; an HOTP entry keeps a period field but
        // never expires.
        val stepSeconds = if (hasCode && !pinRequired && entry.isTimeBased()) entry.getTokenStepSeconds() else 0
        val remaining = if (stepSeconds > 0) TokenDisplay.remainingSeconds(stepSeconds, nowSeconds * 1000L) else null
        return VaultCardState(
            id = entry.getListId(),
            kind = kind,
            issuer = entry.getIssuer(),
            label = entry.getLabel(),
            thumbnailName = entry.getThumbnailName(),
            tags = entry.getTags().toList(),
            token = token,
            previousToken = if (token != null && entry.isTimeBased() && entry.getType() != Entry.OTPType.MOTP) {
                entry.getPrevOTP()?.takeIf { it.isNotEmpty() }
                    ?.let { TokenDisplay.group(it, appearance.groupTokens) }
            } else {
                null
            },
            counter = if (entry.isCounterBased()) entry.getCounter() else null,
            remainingSeconds = remaining,
            progress = if (remaining != null) TokenDisplay.progress(stepSeconds, nowSeconds * 1000L) else null,
            expiring = remaining != null && remaining <= appearance.expiringSeconds,
            revealed = revealed,
            revealedContent = if (revealed) revealedContent(entry, kind) else null,
            pinRequired = pinRequired,
            copyable = token != null && entry.hasCopyableToken()
        )
    }

    /**
     * The one non-code line a revealed card may print. Only public-facing metadata is used: a note
     * body, the account's mail address, the public half of an SSH key, or a password entry's site.
     * A password entry's stored columns are named, never valued, so an unrecognized export cannot
     * leak a password into the list.
     */
    private fun revealedContent(entry: Entry, kind: VaultEntryKind): String? = when (kind) {
        VaultEntryKind.NOTES -> entry.getNote()
        VaultEntryKind.EMAILS -> entry.getEmailAccount().let { account ->
            account.address.ifBlank { account.name }
        }
        VaultEntryKind.SSH_KEYS -> entry.getSshKeys().publicKey.lineSequence().firstOrNull().orEmpty()
        VaultEntryKind.PASSWORDS -> entry.getPasswordFields().let { fields ->
            val site = listOf("url", "host", "site").firstNotNullOfOrNull { name ->
                fields.value(name).takeIf { it.isNotBlank() }
            }
            site ?: fields.columns().joinToString(", ")
        }
        VaultEntryKind.OTP -> null
    }

    private fun kindOf(entry: Entry): VaultEntryKind = when (EntryListPresentation.categoryOf(entry)) {
        EntryListPresentation.Category.OTP -> VaultEntryKind.OTP
        EntryListPresentation.Category.NOTES -> VaultEntryKind.NOTES
        EntryListPresentation.Category.SSH_KEYS -> VaultEntryKind.SSH_KEYS
        EntryListPresentation.Category.PASSWORDS -> VaultEntryKind.PASSWORDS
        EntryListPresentation.Category.EMAILS -> VaultEntryKind.EMAILS
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
