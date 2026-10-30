package org.shadowice.flocke.andotp.View

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuInflater
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
import java.util.Collections
import java.util.IdentityHashMap
import java.util.function.BiConsumer
import java.util.function.Consumer

import javax.crypto.SecretKey

/** RecyclerView adapter for OTP entry cards, managing sorting, filtering, drag-and-drop, and persistence. */
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
    private var activeQrDialog: AlertDialog? = null
    private var activeSshCopyDialog: AlertDialog? = null
    private var activePasswordDialog: AlertDialog? = null
    private var activeEmailDialog: AlertDialog? = null
    private var activePopupMenu: PopupMenu? = null

    /** The key the entries are encrypted with; null while the vault is locked. */
    var encryptionKey: SecretKey? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = presentation.size()

    override fun getItemId(position: Int): Long = presentation.get(position).getListId()

    fun getEntries(): ArrayList<Entry> = entries.getEntries()

    fun hasIssuer(issuer: String?): Boolean = entries.hasIssuer(issuer)

    fun saveAndRefresh(auto_backup: Boolean) {
        saveAndRefresh(auto_backup, RecyclerView.NO_POSITION)
    }

    fun saveAndRefresh(auto_backup: Boolean, itemPos: Int) {
        updateTagsFilter()
        entriesChanged(itemPos)
        saveEntries(auto_backup)
    }

    /**
     * Takes ownership of [e], adds it (showing a toast on duplicate) and saves the database.
     * Destroys [e] if rejected or if saving fails.
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
            (context as? MainActivity)?.showEntryCategory(e)
        } else {
            e.destroy()
            Toast.makeText(context, R.string.toast_entry_exists, Toast.LENGTH_LONG).show()
        }
    }

    internal data class BatchImportResult(val added: Int, val skipped: Int)

    /**
     * Consumes [incoming], skips identical entries, and commits the whole batch in one vault write.
     * Returns null on failure; no partial batch is inserted into the in-memory list.
     */
    internal fun importEntries(incoming: ArrayList<Entry>): BatchImportResult? {
        val retained = Collections.newSetFromMap(IdentityHashMap<Entry, Boolean>())
        try {
            synchronized(VaultSession.MutationLock) {
                val proposed = entries.getEntries()
                retained.addAll(proposed) // Never destroy an entry already owned by the adapter.
                val key = encryptionKey
                if (key == null || VaultSession.getKey() !== key) return null
                val seen = Collections.newSetFromMap(IdentityHashMap<Entry, Boolean>())
                val distinct = HashSet(proposed)
                val accepted = ArrayList<Entry>()
                var skipped = 0
                for (entry in incoming) {
                    if (!seen.add(entry)) throw IllegalArgumentException("Repeated entry instance")
                    entry.validate()
                    if (!distinct.add(entry)) {
                        skipped++
                    } else {
                        if (proposed.size >= InputPolicy.MAX_ENTRIES) return null
                        proposed.add(entry)
                        accepted.add(entry)
                    }
                }
                if (accepted.isEmpty()) return BatchImportResult(0, skipped)
                val now = System.currentTimeMillis()
                accepted.forEach { it.updateOTP(false); it.setLastUsed(now) }
                val write = persistence.save(proposed, key, settings.getAutoBackupEncryptedPasswordsEnabled(),
                    UiBasedBackgroundTask.OutcomeCallback { outcome -> handleBackupOutcome(outcome) })
                if (write !is VaultWriteResult.Saved) {
                    if (write is VaultWriteResult.RecoveryRequired) loadEntries()
                    return null
                }
                if (!entries.addDistinctEntries(accepted)) {
                    // The committed vault is authoritative if the in-memory list unexpectedly changed.
                    return if (loadEntries()) BatchImportResult(accepted.size, skipped) else null
                }
                retained.addAll(accepted)
                updateTagsFilter()
                entriesChanged(RecyclerView.NO_POSITION)
                (context as? MainActivity)?.showEntryCategory(accepted.first())
                return BatchImportResult(accepted.size, skipped)
            }
        } finally {
            incoming.forEach { if (!retained.contains(it)) it.destroy() }
        }
    }

    /** Maps a position in the displayed list to the position in the persisted list. */
    private fun getRealIndex(displayPosition: Int): Int = presentation.sourceIndex(displayPosition)

    private fun validDisplayedPosition(pos: Int): Boolean = pos >= 0 && pos < presentation.size()

    /** Re-sorts and re-filters the displayed list and notifies the view. */
    private fun entriesChanged(itemPos: Int) {
        clearFilter()
        presentation.refreshTags(settings.getNoTagsToggle(), settings.getTagFunctionality())
        notifyDataSetChanged()

        if (itemPos == RecyclerView.NO_POSITION)
            notifyDataSetChanged()
        else if (validDisplayedPosition(itemPos))
            notifyItemChanged(itemPos)
    }

    /** Rebuilds the tag drawer from the tags that are actually in use. */
    fun updateTagsFilter() {
        val reconciled: HashMap<String, Boolean?> = HashMap()
        for ((tag, state) in presentation.reconcileTags(tagsFilterAdapter.getTags(), tagsFilterAdapter.getActiveTags())) {
            reconciled[tag] = state
        }
        tagsFilterAdapter.setTags(reconciled)
        // Tag counters (#994): show how many entries carry each tag.
        tagsFilterAdapter.setTagCounts(entries.getTagCounts())
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

    /** Retries a previously committed change whose automatic backup did not finish. */
    fun retryPendingAutomaticBackup() {
        persistence.retryPending(encryptionKey,
            UiBasedBackgroundTask.OutcomeCallback { outcome -> handleBackupOutcome(outcome) })
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
            val messageId = if (result.messageId != 0) result.messageId else R.string.backup_toast_export_failed
            Toast.makeText(context, messageId, Toast.LENGTH_LONG).show()
        }
    }

    private fun dismissTransientUi() {
        dialogs.dismissAll()
        ManualEntryDialog.dismissActive()
        activeQrDialog?.dismiss()
        activeQrDialog = null
        activeSshCopyDialog?.dismiss()
        activeSshCopyDialog = null
        activePasswordDialog?.dismiss()
        activePasswordDialog = null
        activeEmailDialog?.dismiss()
        activeEmailDialog = null
        activePopupMenu?.dismiss()
        activePopupMenu = null
    }

    /** Clears secrets held by adapters/views and invalidates outstanding filters/timers. */
    fun lockEntries() {
        encryptionKey = null
        dismissTransientUi()
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
            dismissTransientUi()
            taskHandler.removeCallbacksAndMessages(null)
            for (entry in entries.getEntries()) entry.destroy()
            entries.updateEntries(ArrayList(), false)
            entries.updateEntries(result.takeEntries(), true)
            clearFilter()
            entriesChanged(RecyclerView.NO_POSITION)
            return true
        }
    }

    /** Updates the tag selection; an active search continues to cover the whole vault. */
    fun filterByTags(tags: List<String>) {
        clearFilter()
        presentation.filterByTags(tags, settings.getNoTagsToggle(), settings.getTagFunctionality())

        notifyDataSetChanged()
    }

    fun setCategory(category: EntryListPresentation.Category) {
        clearFilter()
        presentation.setCategory(category)
        presentation.refreshTags(settings.getNoTagsToggle(), settings.getTagFunctionality())
        notifyDataSetChanged()
    }

    fun canExportPasswordArchive(entry: Entry, generation: Long): Boolean {
        synchronized(VaultSession.MutationLock) {
            return VaultSession.isCurrent(generation) && encryptionKey != null && VaultSession.getKey() === encryptionKey &&
                entries.getEntries().any { it === entry } && entry.areDetailsVisible() && entry.isPasswordArchive()
        }
    }

    private fun archiveDigest(entry: Entry): String {
        val bytes = entry.exportPasswordArchive()
        return try { java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } }
        finally { bytes.fill(0) }
    }

    fun passwordArchiveDigest(entry: Entry, generation: Long): String? {
        synchronized(VaultSession.MutationLock) {
            return if (canExportPasswordArchive(entry, generation)) archiveDigest(entry) else null
        }
    }

    fun findPasswordArchive(name: String, digest: String): Entry? {
        synchronized(VaultSession.MutationLock) {
            val generation = VaultSession.generation()
            return entries.getEntries().firstOrNull { it.getLabel() == name &&
                canExportPasswordArchive(it, generation) && archiveDigest(it) == digest }
        }
    }

    /** Copies an archive only if the same entry and session are still authorized. */
    fun passwordArchiveForExport(entry: Entry, generation: Long): ByteArray? {
        synchronized(VaultSession.MutationLock) {
            if (!canExportPasswordArchive(entry, generation)) return null
            return entry.exportPasswordArchive()
        }
    }

    /** Searches asynchronously, or restores the selected tag projection when the query is empty. */
    fun setSearchQuery(query: String) {
        presentation.setSearch(query, settings.getSearchValues())
        if (query.isEmpty()) {
            clearFilter()
            presentation.refreshTags(settings.getNoTagsToggle(), settings.getTagFunctionality())
            notifyDataSetChanged()
        } else {
            getFilter().filter(query)
        }
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
                        (cardVisible && (e.hasNonDefaultTokenStep() || settings.isShowIndividualTimeoutsEnabled()))
            }
        }

        if (change)
            notifyDataSetChanged()
    }

    override fun onBindViewHolder(entryViewHolder: EntryViewHolder, i: Int) {
        entryViewHolder.cancelPendingClicks()
        val entry = presentation.get(i)

        if (!entry.isTimeBased() || entry.getCurrentOTP() == null)
            entry.updateOTP(false)

        if (settings.isHighlightTokenOptionEnabled()) {
            if (!settings.getTapToReveal() || entry.isVisible()) {
                entry.hasColorChanged()
            }
            entryViewHolder.updateColor(entry.getColor())
        }

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
        viewHolder.setCallback(object : EntryViewHolder.Callback {
            override fun onMoveEventStart() {
                callback?.onMoveEventStart()
            }

            override fun onMoveEventStop() {
                callback?.onMoveEventStop()
            }

            override fun onPrivacyClicked(position: Int) {
                synchronized(VaultSession.MutationLock) {
                    val key = encryptionKey
                    if (key == null || VaultSession.getKey() !== key || !validDisplayedPosition(position)) return
                    val entry = presentation.get(position)
                    entry.setDetailsVisible(!entry.areDetailsVisible())
                    val result = persistence.save(entries.getEntries(), key, settings.getAutoBackupEncryptedPasswordsEnabled(),
                        UiBasedBackgroundTask.OutcomeCallback { outcome -> handleBackupOutcome(outcome) })
                    if (result !is VaultWriteResult.Saved) {
                        entry.setDetailsVisible(false)
                        Toast.makeText(context, R.string.privacy_save_failed, Toast.LENGTH_LONG).show()
                    }
                    notifyEntryChanged(entry)
                }
            }

            override fun onMenuButtonClicked(parentView: View, position: Int) {
                showPopupMenu(parentView, position)
            }

            override fun onCopyButtonClicked(text: String, position: Int) {
                if (!establishPinIfNeeded(position)) {
                    copyHandler(position, text, settings.isMinimizeAppOnCopyEnabled())
                }
            }

            override fun onCardSingleClicked(position: Int, text: String) {
                when (settings.getTapSingle()) {
                    Constants.TapMode.REVEAL -> {
                        if (!establishPinIfNeeded(position)) {
                            cardTapToRevealHandler(position)
                        }
                    }
                    Constants.TapMode.COPY -> {
                        if (!establishPinIfNeeded(position)) {
                            copyHandler(position, text, false)
                        }
                    }
                    Constants.TapMode.COPY_BACKGROUND -> {
                        if (!establishPinIfNeeded(position)) {
                            copyHandler(position, text, true)
                        }
                    }
                    Constants.TapMode.SEND_KEYSTROKES -> {
                        if (!establishPinIfNeeded(position)) {
                            sendKeystrokes(position)
                        }
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
                        if (!establishPinIfNeeded(position)) {
                            cardTapToRevealHandler(position)
                        }
                    }
                    Constants.TapMode.COPY -> {
                        if (!establishPinIfNeeded(position)) {
                            copyHandler(position, text, false)
                        }
                    }
                    Constants.TapMode.COPY_BACKGROUND -> {
                        if (!establishPinIfNeeded(position)) {
                            copyHandler(position, text, true)
                        }
                    }
                    Constants.TapMode.SEND_KEYSTROKES -> {
                        if (!establishPinIfNeeded(position)) {
                            sendKeystrokes(position)
                        }
                    }
                    else -> Unit
                }
            }

            override fun onCounterClicked(position: Int) {
                if (!validDisplayedPosition(position)) return
                val realIndex = getRealIndex(position)
                if (!EntryIndexGuard.isValid(realIndex, entries.getEntries().size)) return
                updateEntry(presentation.get(position), entries.getEntry(realIndex), position)
            }

            override fun onCounterLongPressed(position: Int) {
                setCounter(position)
            }
        })

        return viewHolder
    }

    /**
     * Prompts for the mOTP PIN when missing. Returns true when [position] is invalid or a PIN
     * prompt had to be opened instead of executing the immediate card action.
     */
    private fun establishPinIfNeeded(position: Int): Boolean {
        if (!validDisplayedPosition(position)) return true
        val entry = presentation.get(position)

        if (entry.getType() == Entry.OTPType.MOTP && entry.getPin().isEmpty()) {
            establishPIN(position)
            return true
        }
        return false
    }

    private fun copyHandler(position: Int, text: String, dropToBackground: Boolean) {
        if (!validDisplayedPosition(position)) return
        val selected = presentation.get(position)
        if (selected.isPasswordArchive() || selected.isEmail()) return
        if (selected.isSshKey()) {
            chooseSshKeyToCopy(position, dropToBackground)
            return
        }
        val otp = if (selected.isPassword()) selected.getPasswordFields().value("password")
            else if (selected.isNote()) selected.getNote()
            else text.ifEmpty { selected.getCurrentOTP() ?: "" }
        if (otp.isEmpty()) return
        Tools.copyToClipboard(context, otp)
        updateLastUsedAndFrequency(position, getRealIndex(position))
        if (dropToBackground) {
            (context as? MainActivity)?.moveTaskToBack(true)
        }
    }

    /** Explicitly choose one field; do not retain key text in a pending dialog callback. */
    private fun chooseSshKeyToCopy(position: Int, dropToBackground: Boolean) {
        showActionDialog(position, BiConsumer { target, entry ->
            val keys = entry.getSshKeys()
            val choices = ArrayList<Boolean>() // false = public, true = private
            val labels = ArrayList<String>()
            if (keys.publicKey.isNotBlank()) {
                choices.add(false)
                labels.add(context.getString(R.string.ssh_public_key))
            }
            if (keys.privateKey.isNotBlank()) {
                choices.add(true)
                labels.add(context.getString(R.string.ssh_private_key))
            }
            activeSshCopyDialog?.dismiss()
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.choose_ssh_key_to_copy)
                .setItems(labels.toTypedArray()) { _, index ->
                    if (index in choices.indices) withTarget(target, Consumer { actual ->
                        if (!actual.isSshKey()) return@Consumer
                        val current = actual.getSshKeys()
                        val value = if (choices[index]) current.privateKey else current.publicKey
                        if (value.isBlank()) return@Consumer
                        val currentPos = presentation.indexOf(actual)
                        val currentIndex = entries.indexOf(actual)
                        if (!EntryIndexGuard.isValid(currentIndex, entries.getEntries().size)) return@Consumer
                        Tools.copyToClipboard(context, value)
                        updateLastUsedAndFrequency(currentPos, currentIndex)
                        if (dropToBackground) (context as? MainActivity)?.moveTaskToBack(true)
                    })
                }.create()
            activeSshCopyDialog = dialog
            dialog.setOnDismissListener { if (activeSshCopyDialog === dialog) activeSshCopyDialog = null }
            dialog.show()
        })
    }

    private fun cardTapToRevealHandler(position: Int) {
        if (!validDisplayedPosition(position)) return
        val entry = presentation.get(position)
        val realIndex = entries.indexOf(entry)
        if (!EntryIndexGuard.isValid(realIndex, entries.getEntries().size)) return
        val realEntry = entries.getEntry(realIndex)

        if (entry.isVisible()) {
            hideEntry(entry)
        } else {
            realEntry.getHideTask()?.let { taskHandler.removeCallbacks(it) }
            realEntry.setHideTask(Runnable { hideEntry(entry) })
            taskHandler.postDelayed(
                realEntry.getHideTask()!!,
                settings.getTapToRevealTimeout() * 1000L)

            if (entry.isCounterBased()) {
                updateEntry(entry, realEntry, position)
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

        // A delayed reveal timeout can race with lockEntries(), deletion, or a list reload. Once
        // the entry has left the owned list this callback is stale; never index the list at -1.
        if (!EntryIndexGuard.isValid(realIndex, entries.getEntries().size)) return

        entries.getEntry(realIndex).setVisible(false)
        entries.getEntry(realIndex).getHideTask()?.let { taskHandler.removeCallbacks(it) }
        entries.getEntry(realIndex).setHideTask(null)

        val updateNeeded = updateLastUsedAndFrequency(pos, realIndex)

        val currentPos = presentation.indexOf(entry)
        if (currentPos >= 0) {
            presentation.get(currentPos).setVisible(false)

            if (updateNeeded)
                notifyItemChanged(currentPos)
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
            if (entry != null && entry.areDetailsVisible()) command.accept(entry)
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

    /** Records usage; returns false for an invalid index or when resorting refreshed the full view. */
    private fun updateLastUsedAndFrequency(position: Int, realIndex: Int): Boolean {
        val ownedEntries = entries.getEntries()
        if (!EntryIndexGuard.isValid(realIndex, ownedEntries.size)) return false
        val safePosition = position.takeIf { it >= 0 && it < presentation.size() }
        val realEntry = ownedEntries[realIndex]
        val timeStamp = System.currentTimeMillis()
        val entryUsedFrequency = realEntry.getUsedFrequency()

        if (safePosition != null) {
            val displayEntry = presentation.get(safePosition)
            val displayEntryUsedFrequency = displayEntry.getUsedFrequency()
            displayEntry.setLastUsed(timeStamp)
            displayEntry.setUsedFrequency(displayEntryUsedFrequency + 1)
        }

        realEntry.setLastUsed(timeStamp)
        realEntry.setUsedFrequency(entryUsedFrequency + 1)
        saveEntries(false)

        if (presentation.resortAfterUsage()) {
            notifyDataSetChanged()
            return false
        }

        return true
    }

    /** Swaps two entries within the selected tab, only in the unsorted, unfiltered view. */
    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (presentation.canDrag() && validDisplayedPosition(fromPosition) && validDisplayedPosition(toPosition)) {
            entries.swapEntries(presentation.sourceIndex(fromPosition), presentation.sourceIndex(toPosition))

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
                    actual.setThumbnailName(value.name)
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
                    // Persist the updated mOTP PIN immediately so a reload or failed save cannot lose it.
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
                        // saveAndRefresh, not a bare save: the removed entry may have carried the
                        // last use of a tag, so the drawer row (and its entry count) would
                        // otherwise stay selectable and filter the list down to nothing.
                        saveAndRefresh(settings.getAutoBackupEncryptedFullEnabled())
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
        if (!validDisplayedPosition(pos)) return
        val otp = presentation.get(pos).getCurrentOTP()
        if (otp.isNullOrEmpty()) return
        val sendIntent = Intent(Intent.ACTION_SEND)
        sendIntent.setType("text/x-keystrokes")
        sendIntent.putExtra(Intent.EXTRA_TEXT, otp)
        if (sendIntent.resolveActivity(this.context.getPackageManager()) == null) {
            Toast.makeText(context, R.string.toast_no_keystroke_receiver, Toast.LENGTH_LONG).show()
            return
        }
        try {
            this.context.startActivity(sendIntent)
            updateLastUsedAndFrequency(pos, getRealIndex(pos))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_keystroke_receiver, Toast.LENGTH_LONG).show()
        }
    }

    /** Shows the entry as a QR code (if the type can be encoded). */
    private fun showQRCode(pos: Int) {
        if (!validDisplayedPosition(pos) || presentation.get(pos).isNote() || presentation.get(pos).isSshKey() || presentation.get(pos).isPassword() || presentation.get(pos).isPasswordArchive() || presentation.get(pos).isEmail()) return
        val uri: Uri? = Uri.parse(presentation.get(pos).toUriString())
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

            activeQrDialog?.dismiss()
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.dialog_title_qr_code)
                .setPositiveButton(android.R.string.ok, { _, _ -> })
                .setView(image)
                .create()
            activeQrDialog = dialog
            dialog.setOnDismissListener {
                if (activeQrDialog === dialog) activeQrDialog = null
                image.setImageDrawable(null)
            }
            dialog.show()
        } else {
            Toast.makeText(context, R.string.toast_qr_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    /** Shows the per-entry context menu. */
    private fun showPopupMenu(view: View, pos: Int) {
        if (!validDisplayedPosition(pos)) return
        val popupEntry = presentation.get(pos)
        val popupGeneration = VaultSession.generation()
        val popupTarget = EntryActionTarget(popupEntry, popupGeneration)
        val menuItemView = requireNotNull(view.findViewById<View>(R.id.menuButton))
        val popup = PopupMenu(view.context, menuItemView)
        val inflate: MenuInflater = popup.getMenuInflater()
        inflate.inflate(R.menu.menu_popup, popup.getMenu())

        if (popupEntry.isNote() || popupEntry.isSshKey() || popupEntry.isPassword() || popupEntry.isPasswordArchive() || popupEntry.isEmail()) {
            popup.getMenu().findItem(R.id.menu_popup_show_qr_code)?.setVisible(false)
            popup.getMenu().findItem(R.id.menu_send_keystrokes)?.setVisible(false)
        }
        if (popupEntry.isPassword() || popupEntry.isPasswordArchive() || popupEntry.isEmail())
            popup.getMenu().findItem(R.id.menu_popup_edit)?.setVisible(false)
        if (popupEntry.isPasswordArchive())
            popup.getMenu().findItem(R.id.menu_popup_export_password_file)?.setVisible(true)
        if (popupEntry.isPassword())
            popup.getMenu().findItem(R.id.menu_popup_password_details)?.setVisible(true)
        if (popupEntry.isEmail())
            popup.getMenu().findItem(R.id.menu_popup_email_details)?.setVisible(true)
        if (popupEntry.getType() == Entry.OTPType.MOTP) {
            // Look up the mOTP PIN item by ID rather than menu position.
            popup.getMenu().findItem(R.id.menu_popup_establishPin)?.setVisible(true)
        }

        popup.setOnMenuItemClickListener(PopupMenu.OnMenuItemClickListener { item ->
            if (!VaultSession.isCurrent(popupGeneration) ||
                    encryptionKey == null || !popupEntry.areDetailsVisible() ||
                    VaultSession.getKey() !== encryptionKey) {
                return@OnMenuItemClickListener false
            }
            val id = item.getItemId()

            if (id == R.id.menu_popup_email_details) {
                withTarget(popupTarget, Consumer { actual ->
                    if (!actual.isEmail()) return@Consumer
                    val value = actual.getEmailAccount()
                    fun line(json: org.json.JSONObject, key: String): String = json.optString(key, "").orEmpty()
                    fun server(title: String, json: org.json.JSONObject): String = buildString {
                        append(title).append('\n')
                        append("Host: ").append(line(json, "host")).append('\n')
                        append("Port: ").append(json.optInt("port")).append(" · Encryption: ").append(json.optInt("encryption")).append('\n')
                        append("Auth type: ").append(json.optInt("auth_type")).append('\n')
                        append("Username: ").append(line(json, "user")).append('\n')
                        append("Password: ").append(line(json, "password"))
                        for (key in listOf("provider", "realm", "fingerprint", "insecure"))
                            if (json.has(key)) append('\n').append(key).append(": ").append(line(json, key))
                    }
                    val details = buildString {
                        append("Account: ").append(line(value.account, "name")).append('\n')
                        append("Address: ").append(line(value.identity, "email")).append("\n\n")
                        append(server(if (value.account.optInt("protocol") == 1) "Incoming (POP)" else "Incoming (IMAP)", value.account))
                        value.identities.forEachIndexed { index, identity ->
                            append("\n\n").append(server("Outgoing SMTP" + if (index == 0) "" else " #${index + 1}", identity))
                        }
                    }
                    activeEmailDialog?.dismiss()
                    val dialog = AlertDialog.Builder(context).setTitle(R.string.email_show_details)
                        .setMessage(details).setPositiveButton(android.R.string.ok, null).create()
                    activeEmailDialog = dialog
                    dialog.setOnDismissListener { if (activeEmailDialog === dialog) activeEmailDialog = null }
                    dialog.show()
                })
                true
            } else if (id == R.id.menu_popup_password_details) {
                withTarget(popupTarget, Consumer { actual ->
                    if (!actual.isPassword()) return@Consumer
                    val fields = actual.getPasswordFields()
                    val text = fields.columns().indices.joinToString("\n\n") { "${fields.columns()[it]}: ${fields.valueAt(it)}" }
                    activePasswordDialog?.dismiss()
                    val dialog = AlertDialog.Builder(context).setTitle(R.string.password_details)
                        .setMessage(text).setPositiveButton(android.R.string.ok, null).create()
                    activePasswordDialog = dialog
                    dialog.setOnDismissListener { if (activePasswordDialog === dialog) activePasswordDialog = null }
                    dialog.show()
                })
                true
            } else if (id == R.id.menu_popup_export_password_file) {
                withTarget(popupTarget, Consumer { actual ->
                    (context as? MainActivity)?.requestPasswordArchiveExport(actual)
                })
                true
            } else if (id == R.id.menu_popup_edit) {
                withTarget(popupTarget, Consumer { actual ->
                    val hostActivity = context as? MainActivity ?: return@Consumer
                    ManualEntryDialog.show(hostActivity, settings, this, actual,
                        ManualEntryDialog.UpdateCallback {
                            withTarget(popupTarget, Consumer { edited ->
                                saveAndRefresh(settings.getAutoBackupEncryptedFullEnabled(),
                                    presentation.indexOf(edited))
                            })
                        })
                })
                true
            } else if (id == R.id.menu_popup_changeImage) {
                changeThumbnail(presentation.indexOf(popupEntry))
                true
            } else if (id == R.id.menu_popup_establishPin) {
                // pos is the row at menu-open time. A hide timeout can resort the list before the
                // click, so resolve the entry again instead of opening the PIN dialog for whoever
                // now occupies that row.
                val currentPos = presentation.indexOf(popupEntry)
                if (currentPos >= 0) establishPIN(currentPos)
                true
            } else if (id == R.id.menu_popup_remove) {
                removeItem(presentation.indexOf(popupEntry))
                true
            } else if (id == R.id.menu_popup_show_qr_code) {
                showQRCode(presentation.indexOf(popupEntry))
                true
            } else if (id == R.id.menu_send_keystrokes) {
                val currentPos = presentation.indexOf(popupEntry)
                if (!establishPinIfNeeded(currentPos)) {
                    sendKeystrokes(currentPos)
                }
                true
            } else {
                false
            }
        })
        activePopupMenu?.dismiss()
        activePopupMenu = popup
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

    /** Per-tag entry counts for the drawer rows (#994). */
    fun getTagCounts(): Map<String, Int> = entries.getTagCounts()

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
