package com.fairuniversal.offlineotp.View

import com.fairuniversal.offlineotp.Database.Entry

/**
 * The manual editor's vault operations, independent of the list renderer.
 *
 * Implementations retain session validation, duplicate checks, persistence and notifications.
 * [addEntry] and [replaceEntry] take ownership of their incoming drafts, including destroying
 * rejected drafts. On replacement failure the existing live entry must remain unchanged.
 * Neither a displayed row position nor a RecyclerView holder crosses this boundary.
 */
interface EntryEditorHost {
    fun getTags(): List<String>
    fun getTagCounts(): Map<String, Int>
    fun addEntry(entry: Entry)
    fun replaceEntry(existing: Entry, replacement: Entry): Boolean
}
