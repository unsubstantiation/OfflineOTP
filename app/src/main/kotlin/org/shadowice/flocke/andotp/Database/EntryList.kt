package org.shadowice.flocke.andotp.Database

import org.shadowice.flocke.andotp.Utilities.Constants

import java.text.Collator
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashSet
import java.util.concurrent.atomic.AtomicLong

/** The complete entry list; the adapter keeps a sorted/filtered copy of it. */
class EntryList {
    private val entries: ArrayList<Entry> = ArrayList()
    private val currentId: AtomicLong = AtomicLong()

    constructor() {}

    /** Adds the entry unless it is already in the list; returns whether it was added. */
    @Synchronized fun addEntry(newEntry: Entry): Boolean = addEntry(newEntry, false)

    /** Like [addEntry], but replaces an existing equal entry when [update] is true. */
    @Synchronized fun addEntry(newEntry: Entry, update: Boolean): Boolean {
        if (!entries.contains(newEntry)) {
            val newId = currentId.incrementAndGet()
            newEntry.setListId(newId)

            entries.add(newEntry)

            return true
        } else {
            if (update) {
                val oldIdx = entries.indexOf(newEntry)
                val oldEntry = entries[oldIdx]

                newEntry.setListId(oldEntry.getListId())
                entries[oldIdx] = newEntry
            }
        }

        return false
    }

    /** Keeps the entries that are still present and adds or updates the given ones. */
    @Synchronized fun updateEntries(newEntries: ArrayList<Entry>, update: Boolean) {
        entries.retainAll(newEntries)

        for (e in newEntries) {
            addEntry(e, update)
        }
    }

    @Synchronized fun getEntry(pos: Int): Entry = entries[pos]

    /** Swaps two entries (used by drag & drop in the unsorted view). */
    @Synchronized fun swapEntries(fromPosition: Int, toPosition: Int) {
        Collections.swap(entries, fromPosition, toPosition)
    }

    @Synchronized fun removeEntry(pos: Int) {
        entries.removeAt(pos)
    }

    /** Position of the entry, or -1 if it is not in the list. */
    @Synchronized fun indexOf(e: Entry): Int = entries.indexOf(e)

    /** True if the list holds the same entries in the same order. */
    @Synchronized fun isEqual(otherEntries: ArrayList<Entry>): Boolean = entries == otherEntries

    @Synchronized fun getEntries(): ArrayList<Entry> = ArrayList(entries)

    /** True when a non-blank issuer is already present, ignoring surrounding space and case. */
    @Synchronized fun hasIssuer(issuer: String?): Boolean {
        val candidate = issuer?.trim().orEmpty()
        if (candidate.isEmpty()) return false
        return entries.any { existing -> existing.getIssuer().trim().equals(candidate, ignoreCase = true) }
    }

    @Synchronized fun getEntriesSorted(sortMode: Constants.SortMode): ArrayList<Entry> = sortEntries(entries, sortMode)

    companion object {
        /** Returns a copy of [unsortedEntries] sorted by [sortMode]. */
        @JvmStatic
        fun sortEntries(unsortedEntries: ArrayList<Entry>, sortMode: Constants.SortMode): ArrayList<Entry> {
            val sorted = ArrayList(unsortedEntries)

            if (sortMode == Constants.SortMode.ISSUER) {
                Collections.sort(sorted, IssuerComparator())
            } else if (sortMode == Constants.SortMode.LABEL) {
                Collections.sort(sorted, LabelComparator())
            } else if (sortMode == Constants.SortMode.LAST_USED) {
                Collections.sort(sorted, LastUsedComparator())
            } else if (sortMode == Constants.SortMode.MOST_USED) {
                Collections.sort(sorted, MostUsedComparator())
            }

            return sorted
        }
    }

    @Synchronized fun getAllTags(): ArrayList<String> {
        val tags = HashSet<String>()

        for (entry in entries) {
            tags.addAll(entry.getTags())
        }

        return ArrayList(tags)
    }

    /** Counts how many entries carry each tag (for tag-counter UI; see #994). */
    @Synchronized fun getTagCounts(): HashMap<String, Int> {
        val counts = HashMap<String, Int>()
        for (entry in entries) {
            // Count each entry once per tag: imported URIs/documents may repeat a tag, and the
            // drawer shows how many entries carry the tag, not how often it was written.
            for (tag in HashSet(entry.getTags())) {
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts
    }

    @Synchronized fun getFilteredEntries(constraint: CharSequence?, filterValues: List<Constants.SearchIncludes>, sortMode: Constants.SortMode): ArrayList<Entry> {
        val filtered: ArrayList<Entry>

        if (constraint != null && constraint.length != 0) {
            filtered = ArrayList()
            // Locale-independent folding: lowercase(Locale.getDefault()) made the match depend on
            // the app language (under Turkish, "I" folds to "ı", so searching "i" missed it).
            for (i in 0 until entries.size) {
                if (filterValues.contains(Constants.SearchIncludes.LABEL) && entries[i].getLabel().contains(constraint, ignoreCase = true)) {
                    filtered.add(entries[i])
                } else if (filterValues.contains(Constants.SearchIncludes.ISSUER) && entries[i].getIssuer().contains(constraint, ignoreCase = true)) {
                    filtered.add(entries[i])
                } else if (filterValues.contains(Constants.SearchIncludes.TAGS)) {
                    val tags = entries[i].getTags()
                    for (j in 0 until tags.size) {
                        if (tags[j].contains(constraint, ignoreCase = true)) {
                            filtered.add(entries[i])
                            break
                        }
                    }
                }
            }
        } else {
            filtered = entries
        }

        return sortEntries(filtered, sortMode)
    }

    /** Returns the entries matching the tag filter (AND/OR, plus tagless entries if enabled). */
    @Synchronized fun getEntriesFilteredByTags(tags: List<String>, noTags: Boolean, tagFunctionality: Constants.TagFunctionality, sortMode: Constants.SortMode): ArrayList<Entry> {
        val matchingEntries = ArrayList<Entry>()

        for (e in entries) {
            var foundMatchingTag = e.getTags().isEmpty() && noTags

            if (tagFunctionality == Constants.TagFunctionality.AND) {
                if (e.getTags().containsAll(tags)) {
                    foundMatchingTag = true
                }
            } else {
                for (tag in tags) {
                    if (e.getTags().contains(tag)) {
                        foundMatchingTag = true
                        break
                    }
                }
            }

            if (foundMatchingTag) {
                matchingEntries.add(e)
            }
        }

        return sortEntries(matchingEntries, sortMode)
    }

    class IssuerComparator : Comparator<Entry> {
        private val collator: Collator

        constructor() {
            collator = Collator.getInstance()
            collator.strength = Collator.PRIMARY
        }

        @Synchronized
        override fun compare(o1: Entry, o2: Entry): Int {
            return collator.compare(o1.getIssuer(), o2.getIssuer())
        }
    }

    class LabelComparator : Comparator<Entry> {
        private val collator: Collator

        constructor() {
            collator = Collator.getInstance()
            collator.strength = Collator.PRIMARY
        }

        @Synchronized
        override fun compare(o1: Entry, o2: Entry): Int {
            return collator.compare(o1.getLabel(), o2.getLabel())
        }
    }

    class LastUsedComparator : Comparator<Entry> {
        @Synchronized
        override fun compare(o1: Entry, o2: Entry): Int {
            return java.lang.Long.compare(o2.getLastUsed(), o1.getLastUsed())
        }
    }

    class MostUsedComparator : Comparator<Entry> {
        @Synchronized
        override fun compare(o1: Entry, o2: Entry): Int {
            return java.lang.Long.compare(o2.getUsedFrequency(), o1.getUsedFrequency())
        }
    }
}
