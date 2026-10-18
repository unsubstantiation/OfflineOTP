package org.shadowice.flocke.andotp.View

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.Toast

import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView

import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

import org.shadowice.flocke.andotp.Activities.MainActivity
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult
import org.shadowice.flocke.andotp.Tasks.TaskOutcome
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.EntryPersistence
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.Tools
import org.shadowice.flocke.andotp.Utilities.VaultReadResult
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult
import org.shadowice.flocke.andotp.View.ItemTouchHelper.ItemTouchHelperAdapter

import java.util.ArrayList
import java.util.HashMap
import java.util.function.BiConsumer
import java.util.function.Consumer

import javax.crypto.SecretKey

/**
 * Adapter for the entry cards; keeps the persisted [EntryList] and the (sorted and filtered) list
 * that is actually displayed.
 *
 * Converted from the Java class of the same name. The sorting, the filtering, the drag & drop and
 * the entry mutations are unchanged.
 *
 * Documented delta: the Java `getEncryptionKey`/`setEncryptionKey` pair becomes the `encryptionKey`
 * property, which is a read/write field exactly like the Java field was.
 */
open class EntriesCardAdapter(context: Context, private val tagsFilterAdapter: TagsAdapter) :
    RecyclerView.Adapter<EntryViewHolder>(), ItemTouchHelperAdapter, Filterable {

    private val context: Context = context
    private val taskHandler: Handler = Handler(Looper.getMainLooper())
    private var filter: EntryFilter? = null
    private val entries: EntryList = EntryList()
    private val presentation: EntryListPresentation = EntryListPresentation(entries)
    private val persistence: EntryPersistence = EntryPersistence(context)
    private val settings: Settings = Settings(context)
    private val dialogs: EntryActionDialogs = EntryActionDialogs(context, settings)
    private var callback: Callback? = null

    /** The key the entries are encrypted with; null while the vault is locked. */
    var encryptionKey: SecretKey? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = presentation.size()

    override fun getItemId(position: Int): Long = presentation.get(position).getListId()

    fun getEntries(): ArrayList<Entry> = entries.getEntries()

    fun saveAndRefresh(auto_backup: Boolean) {
        saveAndRefresh(auto_backup, RecyclerView.NO_POSITION)
    }

    fun saveAndRefresh(auto_backup: Boolean, itemPos: Int) {
        updateTagsFilter()
        entriesChanged(itemPos)
        saveEntries(auto_backup)
    }

    /** Adds the entry (with a toast if it is already present) and saves the database. */
    /**
     * Takes ownership of [e]. It is kept only when the list accepts it; a rejected or failed entry
     * is destroyed so its secret does not outlive the attempt.
     */
    fun addEntry(e: Entry) {
        val added = try {
            if (encryptionKey == null || VaultSession.getKey() !== encryptionKey)
                throw IllegalStateException("Vault locked")
            e.validate()
            if (entries.getEntries().size >= InputPolicy.MAX_ENTRIES)
                throw IllegalArgumentException("Too many entries")
            entries.addEntry(e)
        } catch (error: RuntimeException) {
            e.destroy()
            throw error
        }
        if (added) {
            saveAndRefresh(settings.getAutoBackupEncryptedPasswordsEnabled())
        } else {
            e.destroy()
            Toast.makeText(context, R.string.toast_entry_exists, Toast.LENGTH_LONG).show()
        }
    }

    /** Maps a position in the displayed list to the position in the persisted list. */
    private fun getRealIndex(displayPosition: Int): Int = presentation.sourceIndex(displayPosition)

    /** Re-sorts and re-filters the displayed list and notifies the view. */
    private fun entriesChanged(itemPos: Int) {
        clearFilter()
        presentation.refreshTags(settings.getNoTagsToggle(), settings.getTagFunctionality())
        notifyDataSetChanged()

        if (itemPos == RecyclerView.NO_POSITION)
            notifyDataSetChanged()
        else
            notifyItemChanged(itemPos)
    }

    /** Rebuilds the tag drawer from the tags that are actually in use. */
    fun updateTagsFilter() {
        val reconciled: HashMap<String, Boolean?> = HashMap()
        for ((tag, state) in presentation.reconcileTags(tagsFilterAdapter.getTags(), tagsFilterAdapter.getActiveTags())) {
            reconciled[tag] = state
        }
        tagsFilterAdapter.setTags(reconciled)
        presentation.selectTags(tagsFilterAdapter.getActiveTags())
    }

    /** Saves the database and, if enabled, writes an encrypted automatic backup. */
    fun saveEntries(auto_backup: Boolean) {
        synchronized(VaultSession.MutationLock) {
            val result = persistence.save(entries.getEntries(), encryptionKey, auto_backup,
                UiBasedBackgroundTask.OutcomeCallback { outcome -> handleBackupOutcome(outcome) })
            if (result !is VaultWriteResult.Saved) {
                // Revert unsaved UI mutations; never export them as a successful backup.
                loadEntries()
                Toast.makeText(context, R.string.backup_toast_import_save_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleBackupOutcome(outcome: TaskOutcome<BackupTaskResult>) {
        if (outcome is TaskOutcome.Value)
            handleTaskResult(outcome.value)
        else if (outcome is TaskOutcome.Failure)
            Toast.makeText(context, R.string.backup_toast_export_failed, Toast.LENGTH_LONG).show()
        // Cancellation is not a successful export and must not emit a success toast.
    }

    private fun handleTaskResult(result: BackupTaskResult) {
        if (result.success) {
            Toast.makeText(context, R.string.backup_toast_export_success, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, result.messageId, Toast.LENGTH_LONG).show()
        }
    }

    /** Clears secrets held by adapters/views and invalidates outstanding filters/timers. */
    fun lockEntries() {
        encryptionKey = null
        dialogs.dismissAll()
        taskHandler.removeCallbacksAndMessages(null)
        for (entry in entries.getEntries()) entry.destroy()
        entries.updateEntries(ArrayList(), false)
        presentation.clear()
        filter = null
        notifyDataSetChanged()
    }

    fun loadEntries(): Boolean {
        synchronized(VaultSession.MutationLock) {
            if (encryptionKey == null || VaultSession.getKey() !== encryptionKey) {
                lockEntries()
                return false
            }
            val result = persistence.load(encryptionKey)
            if (result !is VaultReadResult.Loaded) {
                lockEntries()
                return false
            }
            dialogs.dismissAll()
            taskHandler.removeCallbacksAndMessages(null)
            for (entry in entries.getEntries()) entry.destroy()
            entries.updateEntries(ArrayList(), false)
            entries.updateEntries(result.takeEntries(), true)
            clearFilter()
            entriesChanged(RecyclerView.NO_POSITION)
            return true
        }
    }

    /** Shows only the entries matching the given tags. */
    fun filterByTags(tags: List<String>) {
        clearFilter()
        presentation.filterByTags(tags, settings.getNoTagsToggle(), settings.getTagFunctionality())

        notifyDataSetChanged()
    }

    /** Refreshes all time-based tokens; the view is only notified when something changed. */
    fun updateTimeBasedTokens() {
        var change = false

        for (e in entries.getEntries()) {
            if (e.isTimeBased()) {
                val cardVisible = !settings.getTapToReveal() || e.isVisible()

                val item_changed = e.updateOTP(false)
                var color_changed = false

                // Check color change only if highlighting token feature is enabled and the entry is visible
                if (settings.isHighlightTokenOptionEnabled())
                    color_changed = cardVisible && e.hasColorChanged()

                change = change || item_changed || color_changed ||
                        (cardVisible && (e.hasNonDefaultPeriod() || settings.isShowIndividualTimeoutsEnabled()))
            }
        }

        if (change)
            notifyDataSetChanged()
    }

    override fun onBindViewHolder(entryViewHolder: EntryViewHolder, i: Int) {
        val entry = presentation.get(i)

        if (!entry.isTimeBased())
            entry.updateOTP(false)

        if (settings.isHighlightTokenOptionEnabled())
            entryViewHolder.updateColor(entry.getColor())

        entryViewHolder.updateValues(entry)

        entryViewHolder.setLabelSize(settings.getLabelSize())
        entryViewHolder.setLabelScroll(settings.getLabelDisplay(), settings.getCardLayout())

        if (settings.getThumbnailVisible())
            entryViewHolder.setThumbnailSize(settings.getThumbnailSize())
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
        viewHolder.setCallback(object : EntryViewHolder.Callback {
            override fun onMoveEventStart() {
                callback?.onMoveEventStart()
            }

            override fun onMoveEventStop() {
                callback?.onMoveEventStop()
            }

            override fun onMenuButtonClicked(parentView: View, position: Int) {
                showPopupMenu(parentView, position)
            }

            override fun onCopyButtonClicked(text: String, position: Int) {
                copyHandler(position, text, settings.isMinimizeAppOnCopyEnabled())
            }

            override fun onCardSingleClicked(position: Int, text: String) {
                when (settings.getTapSingle()) {
                    Constants.TapMode.REVEAL -> {
                        establishPinIfNeeded(position)
                        cardTapToRevealHandler(position)
                    }
                    Constants.TapMode.COPY -> {
                        establishPinIfNeeded(position)
                        copyHandler(position, text, false)
                    }
                    Constants.TapMode.COPY_BACKGROUND -> {
                        establishPinIfNeeded(position)
                        copyHandler(position, text, true)
                    }
                    Constants.TapMode.SEND_KEYSTROKES -> {
                        establishPinIfNeeded(position)
                        sendKeystrokes(position)
                    }
                    else -> {
                        // If tap-to-reveal is disabled a single tap still needs to establish the PIN
                        if (!settings.getTapToReveal())
                            establishPinIfNeeded(position)
                    }
                }
            }

            override fun onCardDoubleClicked(position: Int, text: String) {
                when (settings.getTapDouble()) {
                    Constants.TapMode.REVEAL -> {
                        establishPinIfNeeded(position)
                        cardTapToRevealHandler(position)
                    }
                    Constants.TapMode.COPY -> {
                        establishPinIfNeeded(position)
                        copyHandler(position, text, false)
                    }
                    Constants.TapMode.COPY_BACKGROUND -> {
                        establishPinIfNeeded(position)
                        copyHandler(position, text, true)
                    }
                    Constants.TapMode.SEND_KEYSTROKES -> {
                        establishPinIfNeeded(position)
                        sendKeystrokes(position)
                    }
                    else -> Unit
                }
            }

            override fun onCounterClicked(position: Int) {
                updateEntry(presentation.get(position), entries.getEntry(getRealIndex(position)), position)
            }

            override fun onCounterLongPressed(position: Int) {
                setCounter(position)
            }
        })

        return viewHolder
    }

    private fun establishPinIfNeeded(position: Int) {
        val entry = presentation.get(position)

        if (entry.getType() == Entry.OTPType.MOTP && entry.getPin().isEmpty())
            establishPIN(position)
    }

    private fun copyHandler(position: Int, text: String, dropToBackground: Boolean) {
        Tools.copyToClipboard(context, text)
        updateLastUsedAndFrequency(position, getRealIndex(position))
        if (dropToBackground) {
            (context as MainActivity).moveTaskToBack(true)
        }
    }

    private fun cardTapToRevealHandler(position: Int) {
        val entry = presentation.get(position)
        val realIndex = entries.indexOf(entry)

        if (entry.isVisible()) {
            hideEntry(entry)
        } else {
            entries.getEntry(realIndex).setHideTask(Runnable { hideEntry(entry) })
            taskHandler.postDelayed(
                entries.getEntry(realIndex).getHideTask()!!,
                settings.getTapToRevealTimeout() * 1000L)

            if (entry.isCounterBased()) {
                updateEntry(entry, entries.getEntry(realIndex), position)
            }
            entry.setVisible(true)
            notifyItemChanged(position)
        }
    }

    private fun updateEntry(entry: Entry, realEntry: Entry, position: Int) {
        if (entry.getCounter() >= Long.MAX_VALUE - 1) return
        val counter = entry.getCounter() + 1

        entry.setCounter(counter)
        entry.updateOTP(false)
        notifyItemChanged(position)

        realEntry.setCounter(counter)
        realEntry.updateOTP(false)

        saveEntries(settings.getAutoBackupEncryptedFullEnabled())
    }

    private fun hideEntry(entry: Entry) {
        val pos = presentation.indexOf(entry)
        val realIndex = entries.indexOf(entry)

        if (realIndex >= 0) {
            entries.getEntry(realIndex).setVisible(false)
            entries.getEntry(realIndex).getHideTask()?.let { taskHandler.removeCallbacks(it) }
            entries.getEntry(realIndex).setHideTask(null)
        }

        val updateNeeded = updateLastUsedAndFrequency(pos, realIndex)

        if (pos >= 0) {
            presentation.get(pos).setVisible(false)

            if (updateNeeded)
                notifyItemChanged(pos)
        }
    }

    private fun showActionDialog(position: Int, show: BiConsumer<EntryActionTarget, Entry>) {
        synchronized(VaultSession.MutationLock) {
            if (encryptionKey == null || VaultSession.getKey() !== encryptionKey
                    || position < 0 || position >= presentation.size()) return
            val entry = presentation.get(position)
            show.accept(EntryActionTarget(entry, VaultSession.generation()), entry)
        }
    }

    /** Commands from the extracted dialogs re-resolve identity and authority before mutation. */
    private fun withTarget(target: EntryActionTarget, command: Consumer<Entry>) {
        synchronized(VaultSession.MutationLock) {
            if (encryptionKey == null || VaultSession.getKey() !== encryptionKey) return
            val entry = target.resolve(entries, VaultSession.generation())
            if (entry != null) command.accept(entry)
        }
    }

    private fun notifyEntryChanged(entry: Entry) {
        val position = presentation.indexOf(entry)
        if (position >= 0) notifyItemChanged(position)
    }

    private fun setCounter(position: Int) {
        showActionDialog(position, BiConsumer { target, entry ->
            dialogs.counter(entry.getCounter(), java.util.function.LongConsumer { value ->
                withTarget(target, Consumer { actual ->
                    actual.setCounter(value)
                    notifyEntryChanged(actual)
                    saveEntries(settings.getAutoBackupEncryptedFullEnabled())
                })
            })
        })
    }

    /** Records the usage; returns false when the resorting also updated the view. */
    private fun updateLastUsedAndFrequency(position: Int, realIndex: Int): Boolean {
        val timeStamp = System.currentTimeMillis()
        val entryUsedFrequency = entries.getEntry(realIndex).getUsedFrequency()

        if (position >= 0) {
            val displayEntryUsedFrequency = presentation.get(position).getUsedFrequency()
            presentation.get(position).setLastUsed(timeStamp)
            presentation.get(position).setUsedFrequency(displayEntryUsedFrequency + 1)
        }

        entries.getEntry(realIndex).setLastUsed(timeStamp)
        entries.getEntry(realIndex).setUsedFrequency(entryUsedFrequency + 1)
        saveEntries(false)

        if (presentation.resortAfterUsage()) {
            notifyDataSetChanged()
            return false
        }

        return true
    }

    /** Swaps two entries after a drag (only in the unsorted, unfiltered view). */
    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (presentation.canDrag()) {
            entries.swapEntries(fromPosition, toPosition)

            presentation.showReorderedEntries()
            notifyItemMoved(fromPosition, toPosition)

            saveEntries(false)
        }

        return true
    }

    /** Dialogs render inputs; identity-based commands stay at the authorized mutation boundary. */
    fun changeThumbnail(position: Int) {
        showActionDialog(position, BiConsumer { target, entry ->
            dialogs.thumbnail(entry.getIssuer(), entry.getLabel(), Consumer { value ->
                withTarget(target, Consumer { actual ->
                    actual.setThumbnail(value)
                    saveEntries(settings.getAutoBackupEncryptedFullEnabled())
                    notifyEntryChanged(actual)
                })
            })
        })
    }

    fun establishPIN(position: Int) {
        showActionDialog(position, BiConsumer { target, entry ->
            dialogs.pin(entry.getPin(), Consumer { value ->
                withTarget(target, Consumer { actual ->
                    actual.setPin(value)
                    actual.updateOTP(true)
                    // The PIN is part of the stored document now, so it has to reach the vault
                    // here; otherwise a reload (or a failed-save revert) would drop it again.
                    saveEntries(settings.getAutoBackupEncryptedFullEnabled())
                    notifyEntryChanged(actual)
                })
            })
        })
    }

    fun removeItem(position: Int) {
        showActionDialog(position, BiConsumer { target, entry ->
            dialogs.delete(entry.getLabel(), Runnable {
                withTarget(target, Consumer { actual ->
                    val index = entries.indexOf(actual)
                    val displayedIndex = presentation.indexOf(actual)
                    if (displayedIndex >= 0) {
                        presentation.removeDisplayed(displayedIndex)
                        notifyItemRemoved(displayedIndex)
                    }
                    entries.removeEntry(index)
                    actual.getHideTask()?.let { taskHandler.removeCallbacks(it) }
                    actual.setHideTask(null)
                    try {
                        saveEntries(settings.getAutoBackupEncryptedFullEnabled())
                    } finally {
                        actual.destroy()
                    }
                })
            })
        })
    }

    /**
     * Sends the current token as an ACTION_SEND intent of type "text/x-keystrokes", which apps
     * such as KDE Connect can pick up (e.g. to type it on a connected device).
     */
    private fun sendKeystrokes(pos: Int) {
        val otp = presentation.get(pos).getCurrentOTP()
        val sendIntent = Intent(Intent.ACTION_SEND)
        sendIntent.setType("text/x-keystrokes")
        sendIntent.putExtra(Intent.EXTRA_TEXT, otp)
        if (sendIntent.resolveActivity(this.context.getPackageManager()) != null) {
            this.context.startActivity(sendIntent)
        }
    }

    /** Shows the entry as a QR code (if the type can be encoded). */
    private fun showQRCode(pos: Int) {
        val uri: Uri? = presentation.get(pos).toUri()
        if (uri != null) {
            val bitmap: Bitmap
            try {
                bitmap = BarcodeEncoder().encodeBitmap(uri.toString(), BarcodeFormat.QR_CODE, 0, 0)
            } catch (ignored: Exception) {
                Toast.makeText(context, R.string.toast_qr_failed_to_generate, Toast.LENGTH_LONG).show()
                return
            }
            val drawable = BitmapDrawable(context.resources, bitmap)
            drawable.setFilterBitmap(false)

            val image = ImageView(context)
            image.setAdjustViewBounds(true)
            image.setScaleType(ImageView.ScaleType.FIT_CENTER)
            image.setImageDrawable(drawable)

            AlertDialog.Builder(context)
                .setTitle(R.string.dialog_title_qr_code)
                .setPositiveButton(android.R.string.ok, { _, _ -> })
                .setView(image)
                .create()
                .show()
        } else {
            Toast.makeText(context, R.string.toast_qr_unsuported, Toast.LENGTH_LONG).show()
        }
    }

    /** Shows the per-entry context menu. */
    private fun showPopupMenu(view: View, pos: Int) {
        val menuItemView = requireNotNull(view.findViewById<View>(R.id.menuButton))
        val popup = PopupMenu(view.context, menuItemView)
        val inflate: MenuInflater = popup.getMenuInflater()
        inflate.inflate(R.menu.menu_popup, popup.getMenu())

        if (presentation.get(pos).getType() == Entry.OTPType.MOTP) {
            // Look the item up by id, not by position: `menu_popup_establishPin` is the fourth
            // <item> in R.menu.menu_popup (index 3), and a positional lookup silently made the
            // wrong item visible, leaving the mOTP PIN action unreachable.
            popup.getMenu().findItem(R.id.menu_popup_establishPin)?.setVisible(true)
        }

        popup.setOnMenuItemClickListener(PopupMenu.OnMenuItemClickListener { item ->
            val id = item.getItemId()

            if (id == R.id.menu_popup_edit) {
                ManualEntryDialog.show(context as MainActivity, settings, this,
                    entries.getEntry(getRealIndex(pos)),
                    ManualEntryDialog.UpdateCallback {
                        saveAndRefresh(settings.getAutoBackupEncryptedFullEnabled(), pos)
                    })
                true
            } else if (id == R.id.menu_popup_changeImage) {
                changeThumbnail(pos)
                true
            } else if (id == R.id.menu_popup_establishPin) {
                establishPIN(pos)
                true
            } else if (id == R.id.menu_popup_remove) {
                removeItem(pos)
                true
            } else if (id == R.id.menu_popup_show_qr_code) {
                showQRCode(pos)
                true
            } else if (id == R.id.menu_send_keystrokes) {
                sendKeystrokes(pos)
                true
            } else {
                false
            }
        })
        popup.show()
    }

    /** Changes the sort order of the displayed entries. */
    fun setSortMode(mode: Constants.SortMode) {
        presentation.setSortMode(mode)
        entriesChanged(RecyclerView.NO_POSITION)
    }

    fun getSortMode(): Constants.SortMode = presentation.getSortMode()

    fun setCallback(cb: Callback?) {
        this.callback = cb
    }

    override fun getFilter(): Filter {
        var current = filter
        if (current == null) {
            current = EntryFilter()
            filter = current
        }

        return current
    }

    /** Drops the current search filter. */
    fun clearFilter() {
        presentation.invalidateSearch()
        filter = null
    }

    fun getTags(): List<String> = entries.getAllTags()

    /** Search filter that applies the configured search fields. */
    inner class EntryFilter : Filter() {
        private val epoch: Long = VaultSession.generation()
        private val filterValues: List<Constants.SearchIncludes> = settings.getSearchValues()

        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val filtered = presentation.search(constraint, filterValues)

            val filterResults = FilterResults()
            filterResults.count = filtered.size()
            filterResults.values = filtered

            return filterResults
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            if (results.values !is EntryListPresentation.SearchResult) return
            val projection = results.values as EntryListPresentation.SearchResult
            synchronized(VaultSession.MutationLock) {
                if (filter !== this || !VaultSession.isCurrent(epoch) || encryptionKey == null
                        || VaultSession.getKey() !== encryptionKey) {
                    projection.close()
                    return
                }
                if (presentation.publishSearch(projection)) notifyDataSetChanged()
            }
        }
    }

    /** Lets the activity react to drag/drop events (pausing the token updater). */
    interface Callback {
        fun onMoveEventStart()
        fun onMoveEventStop()
    }
}
