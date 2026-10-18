/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 * Copyright (C) 2017-2020 Richy HBM
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.View

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.Settings
import java.util.ArrayList
import java.util.HashMap

/** Adapter for the tag lists (drawer and entry dialog); keeps tags sorted by name. */
open class TagsAdapter(context: Context, tags: HashMap<String, Boolean?>) :
    ArrayAdapter<String>(context, LAYOUT_RESOURCE_ID, ArrayList(tags.keys)) {

    private var tagsState = tags
    private var tagsOrder: MutableList<String> = ArrayList(tags.keys).apply { sort() }
    private var tagCounts: Map<String, Int> = emptyMap()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val checkedTextView = if (convertView == null) {
            LayoutInflater.from(context).inflate(LAYOUT_RESOURCE_ID, parent, false) as CheckedTextView
        } else {
            convertView as CheckedTextView
        }
        val tag = tagsOrder[position]
        val count = tagCounts[tag]
        checkedTextView.setText(if (count != null) "$tag ($count)" else tag)
        // Match Java Boolean unboxing: a caller-supplied null is not silently treated as false.
        checkedTextView.isChecked = tagsState[tag]!!
        return checkedTextView
    }

    open fun getTags(): MutableList<String> = tagsOrder

    open fun getTagState(tag: String?): Boolean? = if (tagsState.containsKey(tag)) tagsState[tag] else false

    /** Unknown tags are ignored, but observers are still notified. Boolean stays boxed for Java. */
    open fun setTagState(tag: String?, state: Boolean?) {
        if (tagsState.containsKey(tag)) {
            @Suppress("UNCHECKED_CAST")
            (tagsState as HashMap<String?, Boolean?>)[tag] = state
        }
        notifyDataSetChanged()
    }

    open fun getActiveTags(): MutableList<String> {
        val active = ArrayList<String>()
        for (tag in tagsOrder) {
            if (tagsState[tag]!!) active.add(tag)
        }
        return active
    }

    /** The empty map is vacuously all-active, just as in the Java implementation. */
    open fun allTagsActive(): Boolean {
        for (state in tagsState.values) {
            if (!state!!) return false
        }
        return true
    }

    open fun getTagsWithState(): HashMap<String, Boolean?> = HashMap(tagsState)

    /** Replaces (rather than merges) the backing map, and re-sorts the displayed names. */
    open fun setTags(tags: HashMap<String, Boolean?>) {
        tagsState = tags
        tagsOrder = ArrayList(tags.keys).apply { sort() }
        clear()
        addAll(getTags())
        notifyDataSetChanged()
    }

    /** Updates per-tag entry counts shown alongside the tag name (see #994). */
    open fun setTagCounts(counts: Map<String, Int>) {
        tagCounts = counts
        notifyDataSetChanged()
    }

    open fun getTagCounts(): Map<String, Int> = tagCounts

    companion object {
        private const val LAYOUT_RESOURCE_ID = android.R.layout.simple_list_item_multiple_choice

        /** Restores remembered states and de-duplicates tags across entries. */
        @JvmStatic
        fun createTagsMap(entries: ArrayList<Entry>, settings: Settings): HashMap<String, Boolean?> {
            val tags = HashMap<String, Boolean?>()
            for (entry in entries) {
                for (tag in entry.getTags()) tags[tag] = settings.getTagToggle(tag)
            }
            return tags
        }
    }
}
