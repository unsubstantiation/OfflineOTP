package org.shadowice.flocke.andotp.View

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

class EntryListPresentationTest {
    private val labelSearch = listOf(Constants.SearchIncludes.LABEL)
    private val or = Constants.TagFunctionality.OR

    private fun entry(label: String, tag: String): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Example", label,
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf(tag))

    private fun labels(presentation: EntryListPresentation): List<String> =
        (0 until presentation.size()).map { presentation.get(it).getLabel() }

    @Test
    fun activeSearchSurvivesTagSortAndEntryChangesUntilCleared() {
        val source = EntryList()
        val first = entry("alice-work", "work")
        val second = entry("alice-home", "home")
        val third = entry("bob", "work")
        val entries = listOf(first, second, third)
        try {
            entries.forEach { source.addEntry(it) }
            val presentation = EntryListPresentation(source)
            presentation.filterByTags(listOf("work"), false, or)
            assertEquals(listOf("alice-work", "bob"), labels(presentation))

            presentation.setSearch("alice", labelSearch)
            assertTrue(presentation.publishSearch(presentation.search("alice", labelSearch)))
            // Search intentionally covers all entries, not just the selected tag.
            assertEquals(listOf("alice-work", "alice-home"), labels(presentation))

            presentation.filterByTags(listOf("home"), false, or)
            assertEquals(listOf("alice-work", "alice-home"), labels(presentation))
            third.setLabel("alice-new")
            presentation.setSortMode(Constants.SortMode.LABEL)
            presentation.refreshTags(false, or)
            assertEquals(listOf("alice-home", "alice-new", "alice-work"), labels(presentation))

            second.setLabel("other")
            presentation.refreshTags(false, or)
            assertEquals(listOf("alice-new", "alice-work"), labels(presentation))

            presentation.setSearch("", labelSearch)
            presentation.refreshTags(false, or)
            assertEquals(listOf("other"), labels(presentation))
            presentation.clear()
            assertEquals(0, presentation.size())
            assertEquals("alice-work", first.getLabel()) // the projection never owns entries
        } finally {
            entries.forEach { it.destroy() }
        }
    }

    @Test
    fun legacySearchResultConstructorRemainsUsable() {
        val source = EntryList()
        val entry = entry("alice", "work")
        try {
            source.addEntry(entry)
            val presentation = EntryListPresentation(source)
            val result = EntryListPresentation.SearchResult(presentation, 0L, arrayListOf(entry))
            assertTrue(presentation.publishSearch(result))
            assertEquals(listOf("alice"), labels(presentation))
        } finally {
            entry.destroy()
        }
    }

    @Test
    fun oldResultsAreRejectedEvenIfTheyStartAfterTheQueryChanges() {
        val source = EntryList()
        val oldEntry = entry("old", "work")
        val newEntry = entry("new", "work")
        try {
            source.addEntry(oldEntry)
            source.addEntry(newEntry)
            val presentation = EntryListPresentation(source)
            presentation.setSearch("old", labelSearch)
            val old = presentation.search("old", labelSearch)
            presentation.setSearch("new", labelSearch)
            assertFalse(presentation.publishSearch(old))
            assertNull(old.entries)

            // This worker captured the latest revision but is still answering an obsolete query.
            val lateOld = presentation.search("old", labelSearch)
            assertFalse(presentation.publishSearch(lateOld))
            assertNull(lateOld.entries)
            assertTrue(presentation.publishSearch(presentation.search("new", labelSearch)))
            assertEquals(listOf("new"), labels(presentation))

            val issuerSearch = listOf(Constants.SearchIncludes.ISSUER)
            presentation.setSearch("new", issuerSearch)
            val staleFields = presentation.search("new", labelSearch)
            assertFalse(presentation.publishSearch(staleFields))
            assertNull(staleFields.entries)
            presentation.setSearch("new", listOf(Constants.SearchIncludes.ISSUER, Constants.SearchIncludes.LABEL))
            assertTrue(presentation.publishSearch(presentation.search("new",
                listOf(Constants.SearchIncludes.LABEL, Constants.SearchIncludes.ISSUER))))
            presentation.setSearch("new", labelSearch)

            val beforeRefresh = presentation.search("new", labelSearch)
            newEntry.setLabel("changed")
            presentation.refreshTags(false, or)
            assertFalse(presentation.publishSearch(beforeRefresh))
            assertEquals(emptyList<String>(), labels(presentation))

            presentation.setSearch("", labelSearch)
            // The old request can reach the worker only after the query has been cleared.
            val startedAfterClear = presentation.search("new", labelSearch)
            assertFalse(presentation.publishSearch(startedAfterClear))
            assertNull(startedAfterClear.entries)
            presentation.filterByTags(listOf("work"), false, or)
            assertEquals(listOf("old", "changed"), labels(presentation))
        } finally {
            oldEntry.destroy()
            newEntry.destroy()
        }
    }
}
