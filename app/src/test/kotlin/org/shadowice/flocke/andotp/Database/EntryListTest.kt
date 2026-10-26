/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the in-memory entry list: identity/ids, search, tag filtering and sorting.
 */
package org.shadowice.flocke.andotp.Database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import java.util.Locale

class EntryListTest {
    private fun entry(label: String, issuer: String = "", tags: List<String> = emptyList()): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList(tags))

    private fun populatedList(): EntryList {
        val list = EntryList()
        list.addEntry(entry("github", "GitHub", listOf("work")))
        list.addEntry(entry("aws", "Amazon", listOf("work", "cloud")))
        list.addEntry(entry("forum", "Meta", listOf("personal")))
        list.addEntry(entry("bank", "MyBank"))
        return list
    }

    @Test
    fun addEntryAssignsUniqueIds() {
        val list = EntryList()
        val first = entry("a")
        val second = entry("b")
        assertTrue(list.addEntry(first))
        assertTrue(list.addEntry(second))
        assertTrue(first.getListId() != second.getListId())
        assertEquals(2, list.getEntries().size)
    }

    @Test
    fun duplicateEntriesAreRejectedUnlessUpdateIsRequested() {
        val list = EntryList()
        assertTrue(list.addEntry(entry("a", "i")))
        val assignedId = list.getEntry(0).getListId()
        assertFalse(list.addEntry(entry("a", "i")))
        assertEquals(1, list.getEntries().size)

        // An equal copy replaces the stored instance in place and inherits its list id.
        val equalCopy = entry("a", "i")
        assertFalse(list.addEntry(equalCopy, true))
        assertEquals(1, list.getEntries().size)
        assertEquals(assignedId, equalCopy.getListId())
        assertTrue(list.getEntry(0) === equalCopy)

        // A different entry is a new row, not a replacement.
        assertTrue(list.addEntry(entry("a", "i2")))
        assertEquals(2, list.getEntries().size)
    }

    @Test
    fun issuerLookupIgnoresCaseAndSurroundingWhitespaceButNotBlankValues() {
        val list = populatedList()
        assertTrue(list.hasIssuer("  gItHuB  "))
        assertTrue(list.hasIssuer("AMAZON"))
        assertFalse(list.hasIssuer(""))
        assertFalse(list.hasIssuer("   "))
        assertFalse(list.hasIssuer(null))
        assertFalse(list.hasIssuer("GitHub Inc"))
    }

    @Test
    fun updateEntriesKeepsExistingEntriesAndAddsNewOnes() {
        val list = populatedList()
        val kept = list.getEntry(0)  // github
        val fresh = entry("new", "NewCorp")

        list.updateEntries(ArrayList(listOf(kept, fresh)), false)
        val labels = list.getEntries().map { it.getLabel() }
        assertEquals(listOf("github", "new"), labels)
    }

    @Test
    fun removeAndSwap() {
        val list = EntryList()
        val a = entry("a")
        val b = entry("b")
        list.addEntry(a)
        list.addEntry(b)
        list.swapEntries(0, 1)
        assertEquals(listOf("b", "a"), list.getEntries().map { it.getLabel() })
        list.removeEntry(0)
        assertEquals(listOf("a"), list.getEntries().map { it.getLabel() })
        assertEquals(0, list.indexOf(a))
        assertEquals(-1, list.indexOf(b))
    }

    @Test
    fun getEntriesReturnsACopy() {
        val list = populatedList()
        val copy = list.getEntries()
        copy.clear()
        assertEquals(4, list.getEntries().size)
    }

    @Test
    fun allTagsAreCollected() {
        val tags = populatedList().getAllTags().sorted()
        assertEquals(listOf("cloud", "personal", "work"), tags)
    }

    @Test
    fun tagCountsCountEveryEntryOnceEvenWhenATagRepeats() {
        val list = EntryList()
        // A repeated tag is accepted by the URI parser and by imported documents, so a single
        // entry must still count as one entry per tag in the drawer.
        list.addEntry(entry("github", "GitHub", listOf("work", "work")))
        list.addEntry(entry("aws", "Amazon", listOf("work", "cloud")))

        val counts = list.getTagCounts()
        assertEquals(2, counts["work"])
        assertEquals(1, counts["cloud"])
        assertEquals(2, counts.size)
    }

    @Test
    fun searchMatchesSelectedFieldsCaseInsensitively() {
        val list = populatedList()

        fun labels(constraint: String, includes: List<Constants.SearchIncludes>): List<String> =
            list.getFilteredEntries(constraint, includes, Constants.SortMode.UNSORTED)
                .map { it.getLabel() }

        assertEquals(listOf("github"), labels("GitH", listOf(Constants.SearchIncludes.LABEL)))
        assertEquals(listOf("github"), labels("Hub", listOf(Constants.SearchIncludes.ISSUER)))
        assertEquals(listOf("github", "aws"), labels("work", listOf(Constants.SearchIncludes.TAGS)))
        // An empty constraint returns everything; a non-matching one nothing.
        assertEquals(4, labels("", listOf(Constants.SearchIncludes.LABEL)).size)
        assertEquals(0, labels("zzz", listOf(Constants.SearchIncludes.LABEL, Constants.SearchIncludes.ISSUER)).size)
        // Only the selected fields are searched: "Amaz" appears in the issuer, not the label.
        assertEquals(0, labels("Amaz", listOf(Constants.SearchIncludes.LABEL)).size)
    }

    @Test
    fun searchMatchesIndependentlyOfTheDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            // Turkish folds "I" to "ı"; a locale-sensitive match would miss these.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val list = EntryList()
            list.addEntry(entry("Instagram", "Istanbul", listOf("INVOICE")))

            fun labels(constraint: String, includes: List<Constants.SearchIncludes>): List<String> =
                list.getFilteredEntries(constraint, includes, Constants.SortMode.UNSORTED)
                    .map { it.getLabel() }

            assertEquals(listOf("Instagram"), labels("instagram", listOf(Constants.SearchIncludes.LABEL)))
            assertEquals(listOf("Instagram"), labels("ISTANBUL", listOf(Constants.SearchIncludes.ISSUER)))
            assertEquals(listOf("Instagram"), labels("invoice", listOf(Constants.SearchIncludes.TAGS)))
            // The Turkish dotted/dotless pair still matches in either spelling.
            assertEquals(listOf("Instagram"), labels("ınstagram", listOf(Constants.SearchIncludes.LABEL)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun tagFilteringSupportsOrAndAndAndTagless() {
        val list = populatedList()
        val unsorted = Constants.SortMode.UNSORTED

        fun labels(tags: List<String>, noTags: Boolean, mode: Constants.TagFunctionality): List<String> =
            list.getEntriesFilteredByTags(tags, noTags, mode, unsorted).map { it.getLabel() }

        assertEquals(listOf("github", "aws"), labels(listOf("work"), false, Constants.TagFunctionality.OR))
        assertEquals(listOf("aws"), labels(listOf("work", "cloud"), false, Constants.TagFunctionality.AND))
        assertEquals(listOf("github", "aws", "forum"), labels(listOf("work", "personal"), false, Constants.TagFunctionality.OR))
        // The AND rule also admits entries carrying more than the requested tags.
        assertEquals(listOf("github", "aws"), labels(listOf("work"), false, Constants.TagFunctionality.AND))
        // Tagless entries are admitted only when requested; OR still requires one of the
        // selected tags for entries that carry tags.
        assertEquals(listOf("bank"), labels(emptyList(), true, Constants.TagFunctionality.OR))
        assertEquals(listOf("github", "aws", "bank"), labels(listOf("work"), true, Constants.TagFunctionality.OR))
        assertEquals(0, labels(emptyList(), false, Constants.TagFunctionality.OR).size)
    }

    @Test
    fun sortingUsesTheSelectedMode() {
        val list = EntryList()
        val zeta = entry("zeta", "Zeta").apply { setLastUsed(30); setUsedFrequency(1) }
        val alpha = entry("alpha", "Alpha").apply { setLastUsed(10); setUsedFrequency(5) }
        val mid = entry("mid", "Mid").apply { setLastUsed(20); setUsedFrequency(3) }
        list.addEntry(zeta)
        list.addEntry(alpha)
        list.addEntry(mid)

        fun labels(mode: Constants.SortMode): List<String> =
            list.getEntriesSorted(mode).map { it.getLabel() }

        assertEquals(listOf("zeta", "alpha", "mid"), labels(Constants.SortMode.UNSORTED))
        assertEquals(listOf("alpha", "mid", "zeta"), labels(Constants.SortMode.LABEL))
        assertEquals(listOf("alpha", "mid", "zeta"), labels(Constants.SortMode.ISSUER))
        // The usage sorts are descending: most recent / most used first.
        assertEquals(listOf("zeta", "mid", "alpha"), labels(Constants.SortMode.LAST_USED))
        assertEquals(listOf("alpha", "mid", "zeta"), labels(Constants.SortMode.MOST_USED))
    }

    @Test
    fun isEqualComparesContentOrder() {
        val list = populatedList()
        assertTrue(list.isEqual(list.getEntries()))
        val reordered = list.getEntries()
        reordered.add(reordered.removeAt(0))
        assertFalse(list.isEqual(reordered))
    }
}
