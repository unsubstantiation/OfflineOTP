package com.fairuniversal.offlineotp.View

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.recyclerview.widget.RecyclerView
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.View.ItemTouchHelper.ItemTouchHelperAdapter
import javax.crypto.SecretKey

/**
 * Test-only legacy renderer, retained for historical card regressions during Compose migration.
 * Not packaged in the app. Production rules and session ownership live in AndroidVaultController.
 */
open class EntriesCardAdapter(private val context: Context, tags: TagsAdapter) :
    RecyclerView.Adapter<EntryViewHolder>(), ItemTouchHelperAdapter, Filterable, EntryEditorHost {
    val controller = AndroidVaultController(context, tags, object : AndroidVaultController.Changes {
        override fun changed() = notifyDataSetChanged()
        override fun itemChanged(position: Int) = notifyItemChanged(position)
        override fun itemMoved(from: Int, to: Int) = notifyItemMoved(from, to)
        override fun itemRemoved(position: Int) = notifyItemRemoved(position)
    })
    private val settings = Settings(context)
    var encryptionKey: SecretKey?
        get() = controller.encryptionKey
        set(value) { controller.encryptionKey = value }

    init { setHasStableIds(true) }
    override fun getItemCount() = controller.getItemCount()
    override fun getItemId(position: Int) = controller.getItemId(position)
    override fun addEntry(e: Entry) = controller.addEntry(e)
    override fun replaceEntry(existing: Entry, replacement: Entry) = controller.replaceEntry(existing, replacement)
    override fun getTags() = controller.getTags()
    override fun getTagCounts() = controller.getTagCounts()
    override fun getFilter(): Filter = controller.getFilter()
    override fun onItemMove(fromPosition: Int, toPosition: Int) = controller.onItemMove(fromPosition, toPosition)
    interface Callback : AndroidVaultController.Callback
    fun setCallback(cb: Callback?) = controller.setCallback(cb)
    fun getEntries() = controller.getEntries()
    fun hasIssuer(issuer: String?) = controller.hasIssuer(issuer)
    fun saveAndRefresh(auto_backup: Boolean) = controller.saveAndRefresh(auto_backup)
    fun saveAndRefresh(auto_backup: Boolean, itemPos: Int) = controller.saveAndRefresh(auto_backup, itemPos)
    fun updateTagsFilter() = controller.updateTagsFilter()
    fun saveEntries(auto_backup: Boolean) = controller.saveEntries(auto_backup)
    fun retryPendingAutomaticBackup() = controller.retryPendingAutomaticBackup()
    fun lockEntries() = controller.lockEntries()
    fun loadEntries() = controller.loadEntries()
    fun filterByTags(tags: List<String>) = controller.filterByTags(tags)
    fun setCategory(category: EntryListPresentation.Category) = controller.setCategory(category)
    fun canExportPasswordArchive(entry: Entry, generation: Long) = controller.canExportPasswordArchive(entry, generation)
    fun passwordArchiveDigest(entry: Entry, generation: Long) = controller.passwordArchiveDigest(entry, generation)
    fun findPasswordArchive(name: String, digest: String) = controller.findPasswordArchive(name, digest)
    fun passwordArchiveForExport(entry: Entry, generation: Long) = controller.passwordArchiveForExport(entry, generation)
    fun setSearchQuery(query: String) = controller.setSearchQuery(query)
    fun updateTimeBasedTokens() = controller.updateTimeBasedTokens()
    fun changeThumbnail(position: Int) = controller.changeThumbnail(position)
    fun establishPIN(position: Int) = controller.establishPIN(position)
    fun removeItem(position: Int) = controller.removeItem(position)
    fun setSortMode(mode: Constants.SortMode) = controller.setSortMode(mode)
    fun getSortMode() = controller.getSortMode()
    fun clearFilter() = controller.clearFilter()
    internal fun importEntries(incoming: ArrayList<Entry>) = controller.importEntries(incoming)

    override fun onBindViewHolder(entryViewHolder: EntryViewHolder, i: Int) {
        entryViewHolder.cancelPendingClicks()
        val entry = controller.prepareEntry(i)
        if (settings.isHighlightTokenOptionEnabled()) entryViewHolder.updateColor(entry.getColor())

        entryViewHolder.updateValues(entry, settings)

        entryViewHolder.setLabelSize(settings.getLabelSize())
        entryViewHolder.setLabelScroll(settings.getLabelDisplay(), settings.getCardLayout())

        if (settings.getThumbnailVisible())
            entryViewHolder.setThumbnailSize(settings.getThumbnailSize())
    }

    override fun onViewRecycled(holder: EntryViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelPendingClicks()
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, i: Int): EntryViewHolder {
        var cardLayout = R.layout.component_card_default

        val layout = settings.getCardLayout()

        if (layout == Constants.CardLayouts.COMPACT) {
            cardLayout = R.layout.component_card_compact
        } else if (layout == Constants.CardLayouts.FULL) {
            cardLayout = R.layout.component_card_full
        }

        val itemView = LayoutInflater.from(viewGroup.context).inflate(cardLayout, viewGroup, false)

        val viewHolder = EntryViewHolder(context, itemView, settings.getTapToReveal())
        viewHolder.setCallback(controller.cardActions)
        return viewHolder
    }
}
