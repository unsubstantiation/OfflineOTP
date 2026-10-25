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
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import org.shadowice.flocke.andotp.Utilities.EntryThumbnail
import org.shadowice.flocke.andotp.Utilities.EntryThumbnail.EntryThumbnails
import org.shadowice.flocke.andotp.Utilities.Settings

/** Adapter for the thumbnail picker grid; supports filtering by name. */
open class ThumbnailSelectionAdapter internal constructor(
    private val context: Context,
    private val issuer: String,
    private val label: String
) : BaseAdapter() {
    private val items = EntryThumbnails.values().toMutableList()
    private val settings = Settings(context)

    open fun filter(filter: String) {
        items.clear()
        for (thumbnail in EntryThumbnails.values()) {
            // Locale-independent: lowercasing with the app locale made the match depend on it
            // (under Turkish, "Instagram" folds to "ınstagram" and no longer matched "instagram").
            if (thumbnail.name.contains(filter, ignoreCase = true)) {
                items.add(thumbnail)
            }
        }
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    // Out-of-range positive positions fall back to Default; negative positions throw.
    override fun getItem(position: Int): Any =
        if (position < getCount()) items[position] else EntryThumbnails.Default

    open fun getRealIndex(displayPosition: Int): Int = (getItem(displayPosition) as EntryThumbnails).ordinal

    override fun getItemId(position: Int): Long = (getItem(position) as EntryThumbnails).ordinal.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val thumbnailSize = settings.getThumbnailSize()
        val imageView = if (convertView == null) {
            ImageView(context).apply {
                layoutParams = AbsListView.LayoutParams(thumbnailSize, thumbnailSize)
            }
        } else {
            convertView as ImageView
        }
        val thumbnail = getItem(position) as EntryThumbnails
        imageView.setImageBitmap(EntryThumbnail.getThumbnailGraphic(context, issuer, label, thumbnailSize, thumbnail))
        imageView.contentDescription = thumbnail.name
        return imageView
    }
}
