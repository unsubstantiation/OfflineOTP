package org.shadowice.flocke.andotp.Database

import org.shadowice.flocke.andotp.Utilities.Constants

import java.util.ArrayList

/**
 * The tag selection rule and display order, shared by both clients.
 *
 * Android's tag drawer and the desktop tag dropdown ask the same question — *does this entry carry
 * the selected tags?* — with the same AND/OR choice and the same optional "tagless entries" switch.
 * The rule lives here so a fix to one client's drawer (or a new tag feature) cannot make the same
 * vault show different entries in the two apps. Tag names are ordered here as well, because both
 * clients list them: case-insensitively, so "Zebra" and "apple" sort the way a reader expects.
 */
object EntryTagFilter {

    /**
     * True when [entry] matches the selection.
     *
     * With [functionality] AND the entry must carry every selected tag; with OR it must carry at
     * least one. A tagless entry matches when [noTags] is on. An empty selection in AND mode
     * therefore matches everything (every entry carries all of nothing), while OR mode with no
     * tags matches only tagless entries when [noTags] is set.
     */
    @JvmStatic
    fun matches(entry: Entry, tags: List<String>, noTags: Boolean,
                functionality: Constants.TagFunctionality): Boolean {
        if (entry.getTags().isEmpty() && noTags) return true
        return if (functionality == Constants.TagFunctionality.AND) entry.getTags().containsAll(tags)
        else tags.any { entry.getTags().contains(it) }
    }

    /** Tag names in the order both clients list them: case-insensitively, then case-sensitively. */
    @JvmStatic
    fun sortedNames(names: Collection<String>): ArrayList<String> {
        val sorted = ArrayList(names)
        // Case-insensitive first, then the natural order so "Apple" and "apple" are stable.
        sorted.sortWith(Comparator<String> { first, second ->
            val folded = String.CASE_INSENSITIVE_ORDER.compare(first, second)
            if (folded != 0) folded else first.compareTo(second)
        })
        return sorted
    }
}
