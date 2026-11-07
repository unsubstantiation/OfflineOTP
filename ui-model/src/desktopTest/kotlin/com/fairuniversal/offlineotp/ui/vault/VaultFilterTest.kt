package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.View.EntryListPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter transitions every host uses.
 *
 * Both existing clients had their own tag-tap code (the Android drawer's multi-select and the
 * desktop filter panel), and `SINGLE` behaviour was the easiest to get subtly different. These are
 * pure value transitions, so they are pinned without a device.
 */
class VaultFilterTest {

    private val base = VaultFilter(category = EntryListPresentation.Category.OTP)

    @Test
    fun orSelectsAndDeselectsSeveralTags() {
        val one = base.withTagToggled("work")
        assertEquals(listOf("work"), one.selectedTags)
        val two = one.withTagToggled("2fa")
        assertEquals(listOf("work", "2fa"), two.selectedTags)
        val back = two.withTagToggled("work")
        assertEquals(listOf("2fa"), back.selectedTags)
        assertTrue(back.selectedTags.isNotEmpty())
    }

    @Test
    fun andAlsoAccumulatesBecauseTheMatcherDecides() {
        val and = base.copy(tagFunctionality = Constants.TagFunctionality.AND)
        val both = and.withTagToggled("work").withTagToggled("admin")
        assertEquals(listOf("work", "admin"), both.selectedTags)
        assertEquals(Constants.TagFunctionality.AND, both.tagFunctionality)
    }

    @Test
    fun singleReplacesTheSelection() {
        val single = base.copy(tagFunctionality = Constants.TagFunctionality.SINGLE)
        val one = single.withTagToggled("work")
        assertEquals(listOf("work"), one.selectedTags)
        val other = one.withTagToggled("admin")
        assertEquals(listOf("admin"), other.selectedTags)
    }

    @Test
    fun singleClearsWhenTheOnlyChipIsTappedAgain() {
        val single = base.copy(tagFunctionality = Constants.TagFunctionality.SINGLE)
        val cleared = single.withTagToggled("work").withTagToggled("work")
        assertEquals(emptyList<String>(), cleared.selectedTags)
        assertFalse(cleared.hasTagFilter)
    }

    @Test
    fun untaggedIsItsOwnSwitch() {
        val untaggedOnly = base.withUntaggedToggled()
        assertTrue(untaggedOnly.untaggedSelected)
        assertTrue(untaggedOnly.selectedTags.isEmpty())
        assertTrue(untaggedOnly.hasTagFilter)
        assertFalse(untaggedOnly.withUntaggedToggled().untaggedSelected)
    }

    @Test
    fun aTagTapNeverTouchesTheUntaggedSwitch() {
        val both = base.withUntaggedToggled().withTagToggled("work")
        assertTrue(both.untaggedSelected)
        assertEquals(listOf("work"), both.selectedTags)
    }

    @Test
    fun clearRemovesEveryFilterButKeepsTheTabAndOrder() {
        val filtered = base
            .copy(sortMode = Constants.SortMode.LABEL, combination = VaultFilterCombination.SEARCH_AND_TAGS)
            .withQuery("git")
            .withTagToggled("work")
            .withUntaggedToggled()
        val cleared = filtered.clearedFilters()
        assertEquals("", cleared.query)
        assertEquals(emptyList<String>(), cleared.selectedTags)
        assertFalse(cleared.untaggedSelected)
        assertFalse(cleared.hasQuery)
        assertFalse(cleared.hasTagFilter)
        assertEquals(EntryListPresentation.Category.OTP, cleared.category)
        assertEquals(Constants.SortMode.LABEL, cleared.sortMode)
        assertEquals(VaultFilterCombination.SEARCH_AND_TAGS, cleared.combination)
    }

    @Test
    fun singleFieldChangesPreserveTheRest() {
        val next = base
            .withQuery("git")
            .withSortMode(Constants.SortMode.MOST_USED)
            .withCategory(EntryListPresentation.Category.NOTES)
        assertEquals("git", next.query)
        assertEquals(Constants.SortMode.MOST_USED, next.sortMode)
        assertEquals(EntryListPresentation.Category.NOTES, next.category)
        assertTrue(next.hasQuery)
        // The category is nullable again, so a host can offer "whole vault".
        assertEquals(null, next.withCategory(null).category)
    }

    @Test
    fun theDefaultSearchCoversLabelIssuerAndTags() {
        assertEquals(
            listOf(
                Constants.SearchIncludes.LABEL,
                Constants.SearchIncludes.ISSUER,
                Constants.SearchIncludes.TAGS
            ),
            VaultFilter().searchIncludes
        )
    }
}
