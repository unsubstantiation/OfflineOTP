/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the tag selection rule shared by Android's drawer and the desktop dropdown: the
 * AND/OR modes, the tagless switch, and the order both clients list tag names in.
 */
package org.shadowice.flocke.andotp.Database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

import java.util.ArrayList

class EntryTagFilterTest {

    private fun entry(label: String, tags: List<String> = emptyList()): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "", label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList(tags))

    @Test
    fun orMatchesAnySelectedTagAndAndRequiresAllOfThem() {
        val both = entry("both", listOf("work", "cloud"))
        val one = entry("one", listOf("work"))
        try {
            assertTrue(EntryTagFilter.matches(both, listOf("work", "cloud"), false,
                Constants.TagFunctionality.AND))
            assertFalse(EntryTagFilter.matches(one, listOf("work", "cloud"), false,
                Constants.TagFunctionality.AND))
            assertTrue(EntryTagFilter.matches(one, listOf("work", "cloud"), false,
                Constants.TagFunctionality.OR))
            assertFalse(EntryTagFilter.matches(one, listOf("cloud"), false,
                Constants.TagFunctionality.OR))
        } finally { both.destroy(); one.destroy() }
    }

    @Test
    fun taglessEntriesMatchOnlyWhenTheSwitchIsOn() {
        val untagged = entry("untagged", emptyList())
        try {
            assertTrue(EntryTagFilter.matches(untagged, emptyList(), true,
                Constants.TagFunctionality.OR))
            assertFalse(EntryTagFilter.matches(untagged, emptyList(), false,
                Constants.TagFunctionality.OR))
            // A tagless entry matches whenever the switch is on, whatever else is selected.
            assertTrue(EntryTagFilter.matches(untagged, listOf("work"), true,
                Constants.TagFunctionality.AND))
        } finally { untagged.destroy() }
    }

    @Test
    fun anEmptySelectionInAndModeMatchesEverything() {
        // Android's drawer relies on this: every entry carries all of nothing.
        val untagged = entry("untagged", emptyList())
        val tagged = entry("tagged", listOf("work"))
        try {
            assertTrue(EntryTagFilter.matches(untagged, emptyList(), false,
                Constants.TagFunctionality.AND))
            assertTrue(EntryTagFilter.matches(tagged, emptyList(), false,
                Constants.TagFunctionality.AND))
        } finally { untagged.destroy(); tagged.destroy() }
    }

    @Test
    fun tagNamesSortCaseInsensitivelyWithAStableTieBreak() {
        val sorted = EntryTagFilter.sortedNames(listOf("zebra", "Apple", "apple", "東京", "Work"))
        assertEquals(listOf("Apple", "apple", "Work", "zebra", "東京"), sorted)
    }

    @Test
    fun theEntryListUsesTheSameRuleAndOrder() {
        val list = EntryList()
        list.addEntry(entry("github", listOf("work")))
        list.addEntry(entry("aws", listOf("work", "cloud")))
        list.addEntry(entry("bank"))

        assertEquals(listOf("cloud", "work"), list.getSortedTags())
        // AND needs every tag: only the entry carrying both "work" and "cloud" qualifies.
        assertEquals(listOf("aws"),
            list.getEntriesFilteredByTags(listOf("work", "cloud"), false,
                Constants.TagFunctionality.AND, Constants.SortMode.UNSORTED).map { it.getLabel() })
        assertEquals(listOf("github", "aws"),
            list.getEntriesFilteredByTags(listOf("work", "cloud"), false,
                Constants.TagFunctionality.OR, Constants.SortMode.UNSORTED).map { it.getLabel() })
        assertEquals(listOf("bank"),
            list.getEntriesFilteredByTags(emptyList(), true,
                Constants.TagFunctionality.OR, Constants.SortMode.UNSORTED).map { it.getLabel() })
    }
}
