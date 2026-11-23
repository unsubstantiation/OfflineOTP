package com.fairuniversal.offlineotp.View

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MenuInflater
import android.view.View
import android.widget.Filter
import android.widget.Filterable
import android.widget.Toast

import androidx.appcompat.widget.PopupMenu

import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

import com.fairuniversal.offlineotp.Activities.MainActivity
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryList
import com.fairuniversal.offlineotp.Dialogs.ManualEntryDialog
import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Tasks.BackupTaskResult
import com.fairuniversal.offlineotp.Tasks.TaskOutcome
import com.fairuniversal.offlineotp.Tasks.UiBasedBackgroundTask
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.Utilities.EntryPersistence
import com.fairuniversal.offlineotp.Utilities.EntryUsage
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.Utilities.PasswordCsvExport
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.Utilities.Tools
import com.fairuniversal.offlineotp.Utilities.VaultReadResult
import com.fairuniversal.offlineotp.Utilities.VaultSession
import com.fairuniversal.offlineotp.Utilities.VaultWriteResult
import com.fairuniversal.offlineotp.ui.host.ComposeDialogHost
import com.fairuniversal.offlineotp.ui.host.EmailDetailsComposeView
import com.fairuniversal.offlineotp.ui.host.EmailPasswordComposeView
import com.fairuniversal.offlineotp.ui.host.EmailSettingsComposeView
import com.fairuniversal.offlineotp.ui.host.EntryCopyComposeView
import com.fairuniversal.offlineotp.ui.host.EntryDetailsComposeView
import com.fairuniversal.offlineotp.ui.host.QrCodeComposeView
import com.fairuniversal.offlineotp.ui.host.TagsComposeView

import java.util.ArrayList
import java.util.HashMap
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.function.BiConsumer
import java.util.function.Consumer

import com.fairuniversal.offlineotp.ui.vault.VaultAppearance
import com.fairuniversal.offlineotp.ui.vault.VaultFilter
import com.fairuniversal.offlineotp.ui.vault.VaultFilterCombination
import com.fairuniversal.offlineotp.ui.vault.VaultProjection
import com.fairuniversal.offlineotp.ui.vault.VaultStrings
import javax.crypto.SecretKey

/** Android vault operations and session/search ownership, independent of the list renderer. */
class AndroidVaultController(
    context: Context,
    private val tagsFilterAdapter: TagsAdapter,
    private val changes: Changes
) : Filterable, EntryEditorHost {

    private val context: Context = context
    private val taskHandler: Handler = Handler(Looper.getMainLooper())
    private var filter: EntryFilter? = null
    private val entries: EntryList = EntryList()
    // Explicit Compose Hide/Reveal persists across reloads and restarts without advancing HOTP.
    private val composeRevealOverrides = IdentityHashMap<Entry, Boolean>()
    private val composeVisibilityKeys = IdentityHashMap<Entry, String>()

    private fun composeVisibilityKey(entry: Entry): String {
        composeVisibilityKeys[entry]?.let { return it }
        val secretBytes = entry.getSecret()
        val computed = try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            fun putText(value: String?) {
                val bytes = (value ?: "").toByteArray(Charsets.UTF_8)
                digest.update(byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte()
                ))
                digest.update(bytes)
            }
            putText(entry.getType()?.name)
            putText(entry.getPeriod().toString())
            putText(entry.getDigits().toString())
            putText(entry.getAlgorithm()?.name)
            putText(entry.getIssuer())
            putText(entry.getLabel())
            putText(entry.getHiddenLabel())
            putText(entry.getSshConnection()?.let { "${it.host}:${it.port}:${it.username}" })
            if (secretBytes != null) {
                digest.update(byteArrayOf(
                    (secretBytes.size ushr 24).toByte(),
                    (secretBytes.size ushr 16).toByte(),
                    (secretBytes.size ushr 8).toByte(),
                    secretBytes.size.toByte()
                ))
                digest.update(secretBytes)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        } finally {
            secretBytes?.fill(0)
        }
        composeVisibilityKeys[entry] = computed
        return computed
    }

    private fun persistedComposeReveal(entry: Entry): Boolean? {
        val prefs = try {
            android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        } catch (_: RuntimeException) {
            return null
        }
        val key = composeVisibilityKey(entry)
        val hidden = prefs.getStringSet(PREF_COMPOSE_HIDDEN_ENTRIES, null)
        if (hidden != null && key in hidden) return false
        val revealed = prefs.getStringSet(PREF_COMPOSE_REVEALED_ENTRIES, null)
        if (revealed != null && key in revealed) return true
        return null
    }

    private fun persistComposeRevealKey(key: String, visible: Boolean, obsoleteKey: String? = null) {
        val prefs = try {
            android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        } catch (_: RuntimeException) {
            return
        }
        val hidden = java.util.HashSet(prefs.getStringSet(PREF_COMPOSE_HIDDEN_ENTRIES, null) ?: emptySet())
        val revealed = java.util.HashSet(prefs.getStringSet(PREF_COMPOSE_REVEALED_ENTRIES, null) ?: emptySet())
        if (obsoleteKey != null && obsoleteKey != key) {
            hidden.remove(obsoleteKey)
            revealed.remove(obsoleteKey)
        }
        if (visible) {
            hidden.remove(key)
            revealed.add(key)
        } else {
            revealed.remove(key)
            hidden.add(key)
        }
        prefs.edit()
            .putStringSet(PREF_COMPOSE_HIDDEN_ENTRIES, hidden)
            .putStringSet(PREF_COMPOSE_REVEALED_ENTRIES, revealed)
            .apply()
    }

    private fun removePersistedComposeReveal(entry: Entry) {
        val key = composeVisibilityKey(entry)
        composeVisibilityKeys.remove(entry)
        val prefs = try {
            android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        } catch (_: RuntimeException) {
            return
        }
        val hidden = java.util.HashSet(prefs.getStringSet(PREF_COMPOSE_HIDDEN_ENTRIES, null) ?: emptySet())
        val revealed = java.util.HashSet(prefs.getStringSet(PREF_COMPOSE_REVEALED_ENTRIES, null) ?: emptySet())
        val changedHidden = hidden.remove(key)
        val changedRevealed = revealed.remove(key)
        if (changedHidden || changedRevealed) {
            prefs.edit()
                .putStringSet(PREF_COMPOSE_HIDDEN_ENTRIES, hidden)
                .putStringSet(PREF_COMPOSE_REVEALED_ENTRIES, revealed)
                .apply()
        }
    }

    private fun toggleComposeReveal(position: Int, entry: Entry): Boolean {
        if (!entry.areDetailsVisible()) {
            // Preserve a way to recover entries concealed by the retired privacy button.
            cardActions.onPrivacyClicked(position)
            if (!entry.areDetailsVisible()) return false
            composeRevealOverrides[entry] = true
        } else {
            composeRevealOverrides[entry] = !composeRevealed(entry)
        }
        val nowRevealed = composeRevealOverrides[entry] == true
        persistComposeRevealKey(composeVisibilityKey(entry), nowRevealed)
        // Retire any old tap-reveal timeout; reveal/hide stays in place until explicitly changed.
        entry.getHideTask()?.let { taskHandler.removeCallbacks(it) }
        entry.setHideTask(null)
        entry.setVisible(nowRevealed)
        // Normal Hide/Reveal never advances HOTP or writes usage counters.
        notifyEntryChanged(entry)
        return true
    }

    private fun composeRevealed(entry: Entry): Boolean = entry.areDetailsVisible() &&
        (composeRevealOverrides[entry] ?: persistedComposeReveal(entry)?.also {
            composeRevealOverrides[entry] = it
        } ?: ((!entry.isTimeBased() && !entry.isCounterBased()) ||
            !settings.getTapToReveal() || entry.isVisible()))
    private val presentation: EntryListPresentation = EntryListPresentation(entries)
    private val persistence: EntryPersistence = EntryPersistence(context)
    private val settings: Settings = Settings(context)
    private val dialogs: EntryActionDialogs = EntryActionDialogs(context, settings)
    private var callback: Callback? = null
    private var activeQrDialog: ComposeDialogHost? = null
    private var activeSshCopyDialog: ComposeDialogHost? = null
    private var activePasswordDialog: ComposeDialogHost? = null
    private var activeEmailDialog: ComposeDialogHost? = null
    private var activeTagsDialog: ComposeDialogHost? = null
    private var activePopupMenu: PopupMenu? = null

    private var composeEditorView: com.fairuniversal.offlineotp.ui.vault.VaultComposeView? = null
    private var composeEditTarget: EntryActionTarget? = null

    fun attachComposeEditor(view: com.fairuniversal.offlineotp.ui.vault.VaultComposeView) {
        if (composeEditorView !== view) {
            composeEditorView?.clearEntryEditor()
            composeEditTarget = null
            composeEditorView = view
        }
    }

    fun openSharedCreation(type: Entry.OTPType = Entry.OTPType.TOTP): Boolean {
        val view = composeEditorView ?: return false
        if (encryptionKey == null || VaultSession.getKey() !== encryptionKey) return false
        val generation = VaultSession.generation()
        val initial = com.fairuniversal.offlineotp.ui.vault.ManualEntryInput(type = type)
        composeEditTarget = null
        view.showEntryEditor(initial, editing = false) { input ->
            synchronized(VaultSession.MutationLock) {
                if (!view.ownsEntryEditor(initial) || !VaultSession.isCurrent(generation) ||
                    encryptionKey == null || VaultSession.getKey() !== encryptionKey) {
                    view.clearEntryEditor(); "The session changed."
                } else {
                    val draft = try { input.build() } catch (_: IllegalArgumentException) { null }
                    if (draft == null) "Invalid entry. Check the fields and re-enter cleared credentials."
                    else {
                        val category = EntryListPresentation.categoryOf(draft)
                        val saved = importEntries(arrayListOf(draft))
                        if (saved?.added == 1) { setCategory(category); null }
                        else "Entry not added (duplicate or save failure). Re-enter cleared credentials to retry."
                    }
                }
            }
        }
        return true
    }

    fun openEmailCreation(): Boolean {
        val view = composeEditorView ?: return false
        if (encryptionKey == null || VaultSession.getKey() !== encryptionKey) return false
        val generation = VaultSession.generation()
        val key = Any()
        composeEditTarget = null
        view.showEmailCreation(key) { input ->
            synchronized(VaultSession.MutationLock) {
                if (!view.ownsEmailCreation(key) || !VaultSession.isCurrent(generation) ||
                    encryptionKey == null || VaultSession.getKey() !== encryptionKey) {
                    view.clearEntryEditor(); "The session changed."
                } else {
                    val draft = try { input.build() } catch (_: Exception) { null }
                    if (draft == null) "Invalid email entry. Check fields and re-enter credentials."
                    else {
                        val category = EntryListPresentation.categoryOf(draft)
                        val saved = importEntries(arrayListOf(draft))
                        if (saved?.added == 1) { setCategory(category); null }
                        else "Not added (duplicate or save failure). Re-enter credentials to retry."
                    }
                }
            }
        }
        return true
    }

    private fun deleteFromComposeEditor(
        view: com.fairuniversal.offlineotp.ui.vault.VaultComposeView,
        target: EntryActionTarget
    ) {
        synchronized(VaultSession.MutationLock) {
            val actual = target.resolve(entries, VaultSession.generation())
            view.clearEntryEditor()
            composeEditTarget = null
            if (actual != null && encryptionKey != null &&
                VaultSession.getKey() === encryptionKey && composeRevealed(actual)) {
                val pos = presentation.indexOf(actual)
                if (pos >= 0) removeItem(pos, requireRevealed = true)
            }
        }
    }

    private fun manageTagsFromComposeEditor(
        view: com.fairuniversal.offlineotp.ui.vault.VaultComposeView,
        target: EntryActionTarget
    ) {
        synchronized(VaultSession.MutationLock) {
            val actual = target.resolve(entries, VaultSession.generation())
            view.clearEntryEditor()
            composeEditTarget = null
            if (actual != null && encryptionKey != null &&
                VaultSession.getKey() === encryptionKey && composeRevealed(actual)) {
                openTagsDialog(target, actual)
            }
        }
    }

    private fun openPasswordDetailsDialog(entry: Entry): Boolean {
        if (!entry.isPassword() || !composeRevealed(entry)) return false
        val activity = context as? Activity ?: return false
        val fields = entry.getPasswordFields()
        val text = com.fairuniversal.offlineotp.Utilities.LinScpCredentials.formatPasswordDetails(fields)
        activePasswordDialog?.dismiss()
        activePasswordDialog = null
        val pending = object { var host: ComposeDialogHost? = null }
        val view = EntryDetailsComposeView(
            activity = activity,
            title = context.getString(R.string.password_details),
            body = text,
            confirmLabel = context.getString(android.R.string.ok),
            onDismiss = {
                pending.host?.dismiss()
                if (activePasswordDialog === pending.host) activePasswordDialog = null
            }
        )
        pending.host = view
        activePasswordDialog = view
        view.show()
        return true
    }

    private fun openTagsDialog(target: EntryActionTarget, entry: Entry): Boolean {
        if (!entry.areDetailsVisible()) return false
        val tagState = HashMap<String, Boolean?>()
        for (tag in getTags()) tagState[tag] = false
        for (tag in entry.getTags()) tagState[tag] = true
        val tagsAdapter = TagsAdapter(context, tagState)
        val liveCounts = getTagCounts()
        tagsAdapter.setTagCounts(liveCounts)
        val saveTags = Callable<Any?> {
            withTarget(target, Consumer { edited ->
                val previous = ArrayList(edited.getTags())
                try {
                    edited.setTags(ArrayList(tagsAdapter.getActiveTags()))
                    edited.validate()
                    saveAndRefresh(settings.getAutoBackupEncryptedFullEnabled(),
                        presentation.indexOf(edited))
                } catch (_: IllegalArgumentException) {
                    edited.setTags(previous)
                }
            })
            null
        }
        val activity = context as? Activity
        if (activity != null) {
            activeTagsDialog?.dismiss()
            activeTagsDialog = null
            val pending = object { var host: ComposeDialogHost? = null }
            val initialStates = LinkedHashMap<String, Boolean>()
            for ((name, checked) in tagState) {
                initialStates[name] = checked == true
            }
            val view = TagsComposeView(
                activity = activity,
                title = context.getString(R.string.label_tags),
                newTagLabel = context.getString(R.string.button_new_tag),
                addTagLabel = context.getString(R.string.button_new_tag),
                confirmLabel = context.getString(android.R.string.ok),
                dismissLabel = context.getString(android.R.string.cancel),
                initialStates = initialStates,
                tagCounts = liveCounts,
                onTagCreated = { created ->
                    val allTags = tagsAdapter.getTagsWithState()
                    allTags[created] = true
                    tagsAdapter.setTags(allTags)
                },
                onSelectionConfirmed = { confirmed ->
                    val updated = HashMap<String, Boolean?>()
                    for ((name, checked) in confirmed) {
                        updated[name] = checked
                    }
                    tagsAdapter.setTags(updated)
                    try { saveTags.call() } catch (_: Exception) { }
                },
                onDismiss = {
                    pending.host?.dismiss()
                    if (activeTagsDialog === pending.host) activeTagsDialog = null
                }
            )
            pending.host = view
            activeTagsDialog = view
            view.show()
            return true
        }
        return false
    }

    private fun openEmailEditor(target: EntryActionTarget, entry: Entry): Boolean {
        val view = composeEditorView ?: return false
        if (!entry.isEmail() || !composeRevealed(entry)) return false
        val initial = com.fairuniversal.offlineotp.ui.vault.EmailEditInput.fromEntry(entry)
        composeEditTarget = target
        view.showEmailEditor(
            initial = initial,
            onManageTags = { manageTagsFromComposeEditor(view, target) },
            onViewDetails = {
                synchronized(VaultSession.MutationLock) {
                    val actual = target.resolve(entries, VaultSession.generation())
                    view.clearEntryEditor()
                    composeEditTarget = null
                    if (actual != null && encryptionKey != null &&
                        VaultSession.getKey() === encryptionKey && actual.isEmail() && composeRevealed(actual)) {
                        openEmailDetailsDialog(target, actual)
                    }
                }
            },
            onDelete = { deleteFromComposeEditor(view, target) }
        ) { input ->
            synchronized(VaultSession.MutationLock) {
                val actual = target.resolve(entries, VaultSession.generation())
                if (!view.ownsEmailEditor(initial) || composeEditTarget !== target || encryptionKey == null ||
                    VaultSession.getKey() !== encryptionKey || actual == null || !composeRevealed(actual)) {
                    view.clearEntryEditor(); "The entry or session changed."
                } else {
                    val replacement = try { input.build(actual) } catch (_: Exception) { null }
                    if (replacement == null) "Invalid email entry. Check field values and JSON structure."
                    else if (replaceEntry(actual, replacement, preserveMissingPin = false)) null
                    else "Could not save email entry. Original retained."
                }
            }
        }
        return true
    }

    private fun openEmailSettingsEditor(target: EntryActionTarget, entry: Entry): Boolean {
        if (!entry.isEmail() || !composeRevealed(entry)) return false
        val activity = context as? Activity ?: return false
        activeEmailDialog?.dismiss()
        activeEmailDialog = null
        val initial = com.fairuniversal.offlineotp.ui.vault.EmailSettingsInput.fromEntry(entry)
        composeEditTarget = target
        val pending = object { var host: ComposeDialogHost? = null }
        val dialog = EmailSettingsComposeView(
            activity = activity,
            initial = initial,
            onSubmit = { input ->
                synchronized(VaultSession.MutationLock) {
                    val actual = target.resolve(entries, VaultSession.generation())
                    if (activeEmailDialog !== pending.host || composeEditTarget !== target || encryptionKey == null ||
                        VaultSession.getKey() !== encryptionKey || actual == null || !composeRevealed(actual)) {
                        pending.host?.dismiss()
                        if (activeEmailDialog === pending.host) activeEmailDialog = null
                        "The entry or session changed."
                    } else {
                        val replacement = try { input.build(actual) } catch (_: Exception) { null }
                        if (replacement == null) "Invalid email settings. Check server, port and sender fields."
                        else if (replaceEntry(actual, replacement, preserveMissingPin = false)) null
                        else "Could not save email settings. Original retained."
                    }
                }
            },
            onDismiss = {
                pending.host?.dismiss()
                if (activeEmailDialog === pending.host) activeEmailDialog = null
            }
        )
        pending.host = dialog
        activeEmailDialog = dialog
        dialog.show()
        return true
    }

    private fun openEmailPasswordEditor(target: EntryActionTarget, entry: Entry): Boolean {
        if (!entry.isEmail() || !composeRevealed(entry)) return false
        val activity = context as? Activity ?: return false
        activeEmailDialog?.dismiss()
        activeEmailDialog = null
        val initial = com.fairuniversal.offlineotp.ui.vault.EmailPasswordInput.fromEntry(entry)
        composeEditTarget = target
        val pending = object { var host: ComposeDialogHost? = null }
        val dialog = EmailPasswordComposeView(
            activity = activity,
            initial = initial,
            onSubmit = { input ->
                synchronized(VaultSession.MutationLock) {
                    val actual = target.resolve(entries, VaultSession.generation())
                    if (activeEmailDialog !== pending.host || composeEditTarget !== target || encryptionKey == null ||
                        VaultSession.getKey() !== encryptionKey || actual == null || !composeRevealed(actual)) {
                        pending.host?.dismiss()
                        if (activeEmailDialog === pending.host) activeEmailDialog = null
                        "The entry or session changed."
                    } else {
                        val replacement = try { input.build(actual) } catch (_: Exception) { null }
                        if (replacement == null) "Invalid email credentials."
                        else if (replaceEntry(actual, replacement, preserveMissingPin = false)) null
                        else "Could not save email credentials. Original retained."
                    }
                }
            },
            onDismiss = {
                pending.host?.dismiss()
                if (activeEmailDialog === pending.host) activeEmailDialog = null
            }
        )
        pending.host = dialog
        activeEmailDialog = dialog
        dialog.show()
        return true
    }

    private fun openEmailDetailsDialog(target: EntryActionTarget, entry: Entry): Boolean {
        if (!entry.isEmail() || !composeRevealed(entry)) return false
        activeEmailDialog?.dismiss()
        activeEmailDialog = null
        val activity = context as? Activity ?: return false
        val pending = object { var host: ComposeDialogHost? = null }
        val view = EmailDetailsComposeView(
            activity = activity,
            rowsProvider = { visible ->
                var rows: List<EmailMaterial.Detail>? = null
                withTarget(target, Consumer { actual ->
                    if (actual.isEmail() && composeRevealed(actual)) {
                        rows = if (visible) {
                            EmailMaterial.details(actual.getEmailAccount(), revealSecrets = true)
                        } else {
                            EmailMaterial.details(actual.getEmailAccount(), revealSecrets = false)
                        }
                    }
                })
                rows
            },
            onDismiss = {
                pending.host?.dismiss()
                if (activeEmailDialog === pending.host) activeEmailDialog = null
            },
            onEditSettings = {
                pending.host?.dismiss()
                if (activeEmailDialog === pending.host) activeEmailDialog = null
                withTarget(target, Consumer { current ->
                    if (current.isEmail()) openEmailSettingsEditor(target, current)
                })
            },
            onEditPasswords = {
                pending.host?.dismiss()
                if (activeEmailDialog === pending.host) activeEmailDialog = null
                withTarget(target, Consumer { current ->
                    if (current.isEmail()) openEmailPasswordEditor(target, current)
                })
            }
        )
        pending.host = view
        activeEmailDialog = view
        view.show()
        return true
    }

    private fun openPasswordEditor(target: EntryActionTarget, entry: Entry): Boolean {
        val view = composeEditorView ?: return false
        if (!composeRevealed(entry) || !com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput.supportsAny(entry)) return false
        val initial = com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput.fromAnyEntry(entry)
        composeEditTarget = target
        view.showPasswordEditor(
            initial = initial,
            onManageTags = { manageTagsFromComposeEditor(view, target) },
            onViewDetails = if (!entry.isPasswordArchive()) ({
                synchronized(VaultSession.MutationLock) {
                    val actual = target.resolve(entries, VaultSession.generation())
                    view.clearEntryEditor()
                    composeEditTarget = null
                    if (actual != null && encryptionKey != null &&
                        VaultSession.getKey() === encryptionKey && actual.isPassword() && composeRevealed(actual)) {
                        openPasswordDetailsDialog(actual)
                    }
                }
            }) else null,
            onExportArchive = if (entry.isPasswordArchive()) ({
                synchronized(VaultSession.MutationLock) {
                    val actual = target.resolve(entries, VaultSession.generation())
                    view.clearEntryEditor()
                    composeEditTarget = null
                    if (actual != null && encryptionKey != null &&
                        VaultSession.getKey() === encryptionKey && actual.isPasswordArchive() && composeRevealed(actual)) {
                        (context as? MainActivity)?.requestPasswordArchiveExport(actual)
                    }
                }
            }) else null,
            onDelete = { deleteFromComposeEditor(view, target) }
        ) { input ->
            synchronized(VaultSession.MutationLock) {
                val actual = target.resolve(entries, VaultSession.generation())
                if (!view.ownsPasswordEditor(initial) || composeEditTarget !== target || encryptionKey == null ||
                    VaultSession.getKey() !== encryptionKey || actual == null || !composeRevealed(actual)) {
                    view.clearEntryEditor(); "The entry or session changed."
                } else {
                    val replacement = try { input.build(actual) } catch (_: Exception) { null }
                    if (replacement == null) "Invalid password entry. Check the fields."
                    else if (replaceEntry(actual, replacement, preserveMissingPin = false)) {
                        composeEditTarget = null; null
                    } else "Could not save the password entry. Original retained."
                }
            }
        }
        return true
    }

    private fun openSharedEditor(target: EntryActionTarget, entry: Entry): Boolean {
        val view = composeEditorView ?: return false
        if (!composeRevealed(entry) || (entry.getType() !in com.fairuniversal.offlineotp.ui.vault.ManualEntryInput.otpTypes && !entry.isNote() && !entry.isSshKey())) return false
        composeEditTarget = target
        val initial = com.fairuniversal.offlineotp.ui.vault.ManualEntryInput.forEntryEdit(entry)
        val canShowQr = entry.hasOtpUri()
        view.showEntryEditor(
            initial = initial,
            editing = true,
            onShowQrCode = if (canShowQr) ({
                synchronized(VaultSession.MutationLock) {
                    val actual = target.resolve(entries, VaultSession.generation())
                    if (actual != null && encryptionKey != null &&
                        VaultSession.getKey() === encryptionKey && composeRevealed(actual) && actual.hasOtpUri()) {
                        val pos = presentation.indexOf(actual)
                        if (pos >= 0) showQRCode(pos)
                    }
                }
            }) else null,
            onShareKeystrokes = if (canShowQr) ({
                synchronized(VaultSession.MutationLock) {
                    val actual = target.resolve(entries, VaultSession.generation())
                    view.clearEntryEditor()
                    composeEditTarget = null
                    if (actual != null && encryptionKey != null &&
                        VaultSession.getKey() === encryptionKey && composeRevealed(actual) && actual.hasOtpUri()) {
                        val pos = presentation.indexOf(actual)
                        if (pos >= 0 && !establishPinIfNeeded(pos)) sendKeystrokes(pos)
                    }
                }
            }) else null,
            onManageTags = { manageTagsFromComposeEditor(view, target) },
            onDelete = { deleteFromComposeEditor(view, target) }
        ) { input ->
            synchronized(VaultSession.MutationLock) {
                val actual = target.resolve(entries, VaultSession.generation())
                if (!view.ownsEntryEditor(initial) || composeEditTarget !== target || encryptionKey == null || VaultSession.getKey() !== encryptionKey ||
                    actual == null || !composeRevealed(actual)) {
                    view.clearEntryEditor()
                    "The entry or session changed."
                } else {
                    val replacement = try { input.build(actual) } catch (_: IllegalArgumentException) { null }
                    if (replacement == null) "Invalid entry. Check the fields and re-enter cleared credentials."
                    else if (replaceEntry(actual, replacement, preserveMissingPin = false)) {
                        composeEditTarget = null
                        null
                    } else "Could not save the entry. Re-enter cleared credentials to retry."
                }
            }
        }
        return true
    }

    /** The key the entries are encrypted with; null while the vault is locked. */
    var encryptionKey: SecretKey? = null

    fun getItemCount(): Int = presentation.size()

    fun getItemId(position: Int): Long = presentation.get(position).getListId()

    /** One appearance mapping for both frame derivation and the Compose view's layout. */
    fun composeAppearance(): VaultAppearance {
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        return VaultAppearance(
            showControls = false,
            showThumbnails = settings.getThumbnailVisible(),
            thumbnailSizeDp = (settings.getThumbnailSize() / density).coerceAtLeast(0f),
            labelSizeSp = settings.getLabelSize().takeIf { it > 0 },
            cardLayout = settings.getCardLayout(),
            labelDisplay = settings.getLabelDisplay(),
            tokenGroupSize = settings.getTokenSplitGroupSize(),
            showIssuer = !settings.isHideIssuerEnabled(),
            showPreviousToken = settings.getShowPrevToken(),
            showIndividualCountdowns = settings.isShowIndividualTimeoutsEnabled(),
            hideCountdownWhenMasked = settings.getTapToReveal(),
            expiringSeconds = if (settings.isHighlightTokenOptionEnabled()) 8 else -1
        )
    }

    /**
     * Read the already-published Android projection. Never call applyFilter here: doing so would
     * bypass EntryFilter's generation/revision-checked asynchronous publication.
     */
    fun composeFrame(nowMillis: Long, strings: VaultStrings): AndroidVaultFrame? {
        synchronized(VaultSession.MutationLock) {
            if (encryptionKey == null || VaultSession.getKey() !== encryptionKey) return null
            val generation = VaultSession.generation()
            composeEditTarget?.let { target ->
                val current = target.resolve(entries, generation)
                if (current == null || !composeRevealed(current)) {
                    composeEditorView?.clearEntryEditor(); composeEditTarget = null
                }
            }
            val selection = presentation.selection()
            val filter = VaultFilter(
                query = selection.query, searchIncludes = selection.includes,
                category = selection.category, sortMode = selection.sortMode,
                selectedTags = selection.tags, untaggedSelected = settings.getNoTagsToggle(),
                tagFunctionality = settings.getTagFunctionality(),
                combination = VaultFilterCombination.SEARCH_OVERRIDES_TAGS
            )
            val shown = (0 until presentation.size()).map { presentation.get(it) }
            val revealed = shown.filter { composeRevealed(it) }.map { it.getListId() }.toSet()
            val appearance = composeAppearance()
            val projected = VaultProjection(entries, presentation).snapshot(filter, nowMillis, revealed, appearance, strings)
            val state = projected.copy(cards = projected.cards.mapIndexed { index, card ->
                val entry = shown[index]
                if (!entry.areDetailsVisible()) card.copy(
                    detailsVisible = false, token = null, previousToken = null, tags = emptyList(),
                    thumbnailName = "", counter = null, remainingSeconds = null, progress = null, customPeriodSeconds = null,
                    expiring = false, revealed = false, revealedContent = null, pinRequired = false,
                    copyable = false, canCopyAccount = false, canCopyPassword = false
                ) else card.copy(
                    detailsVisible = true,
                    detailLabel = entry.getHiddenLabel().ifEmpty {
                        if (entry.isSshKey()) entry.getSshConnection()?.summary().orEmpty() else ""
                    }.takeIf { it.isNotEmpty() && composeRevealed(entry) },
                    copyable = entry.areDetailsVisible() && (card.copyable || entry.isNote() ||
                        (entry.isSshKey() && entry.getSshKeys().let { it.publicKey.isNotBlank() || it.privateKey.isNotBlank() })),
                    canEditPassword = composeRevealed(entry) &&
                        com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput.supportsAny(entry),
                    canCopyAccount = entry.hasCopyableAccount(),
                    canCopyPassword = entry.hasCopyablePassword(),
                    canMoveUp = presentation.canDrag() && index > 0,
                    canMoveDown = presentation.canDrag() && index < shown.lastIndex
                )
            })
            val targets = shown.associate { it.getListId() to EntryActionTarget(it, generation) }
            return AndroidVaultFrame(state) { id, command, anchor ->
                val target = targets[id]
                if (target == null) false else dispatchFrameCommand(target, generation, command, anchor)
            }
        }
    }

    private fun dispatchFrameCommand(
        target: EntryActionTarget, generation: Long, command: AndroidCardCommand, anchor: View?
    ): Boolean {
        synchronized(VaultSession.MutationLock) {
            if (!VaultSession.isCurrent(generation) || encryptionKey == null || VaultSession.getKey() !== encryptionKey)
                return false
            val entry = target.resolve(entries, generation) ?: return false
            val position = presentation.indexOf(entry)
            if (position < 0) return false
            if (command != AndroidCardCommand.PRIVACY && command != AndroidCardCommand.TOGGLE_REVEAL &&
                !entry.areDetailsVisible()) return false
            // Obtain current token text from the live entry, never from a potentially stale frame.
            when (command) {
                AndroidCardCommand.PRIVACY -> cardActions.onPrivacyClicked(position)
                AndroidCardCommand.TOGGLE_REVEAL -> {
                    if (!toggleComposeReveal(position, entry)) return false
                }
                AndroidCardCommand.EDIT -> {
                    val opened = when {
                        entry.isEmail() -> openEmailEditor(target, entry)
                        com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput.supportsAny(entry) ->
                            openPasswordEditor(target, entry)
                        else -> openSharedEditor(target, entry)
                    }
                    if (!opened) return false
                }
                AndroidCardCommand.COPY -> {
                    if (entry.isTimeBased()) entry.updateOTP(false)
                    if ((entry.isTimeBased() || entry.isCounterBased()) && !entry.hasCopyableToken() &&
                        !(entry.getType() == Entry.OTPType.MOTP && entry.getPin().isEmpty())) return false
                    cardActions.onCopyButtonClicked("", position)
                }
                AndroidCardCommand.COPY_ACCOUNT -> {
                    if (!copyAccountValue(entry, settings.isMinimizeAppOnCopyEnabled())) return false
                }
                AndroidCardCommand.COPY_PASSWORD -> {
                    if (!copyPasswordValue(position, entry, settings.isMinimizeAppOnCopyEnabled())) return false
                }
                AndroidCardCommand.REVEAL_TOKEN -> {
                    if (!establishPinIfNeeded(position) && !toggleComposeReveal(position, entry)) return false
                }
                AndroidCardCommand.NEXT_COUNTER -> {
                    if (!entry.isCounterBased()) return false
                    cardActions.onCounterClicked(position)
                }
                AndroidCardCommand.EDIT_COUNTER -> {
                    if (!entry.isCounterBased()) return false
                    cardActions.onCounterLongPressed(position)
                }
                AndroidCardCommand.ENTER_PIN -> {
                    if (entry.getType() != Entry.OTPType.MOTP) return false
                    establishPIN(position)
                }
                AndroidCardCommand.DELETE -> {
                    if (!composeRevealed(entry)) return false
                    removeItem(position, requireRevealed = true)
                }
                AndroidCardCommand.MENU -> {
                    if (anchor == null) return false
                    showPopupMenu(anchor, position)
                }
                AndroidCardCommand.EMAIL_DETAILS -> {
                    if (!openEmailDetailsDialog(target, entry)) return false
                }
                AndroidCardCommand.MOVE_UP, AndroidCardCommand.MOVE_DOWN -> {
                    val to = position + if (command == AndroidCardCommand.MOVE_UP) -1 else 1
                    if (!presentation.canDrag() || !validDisplayedPosition(to)) return false
                    onItemMove(position, to)
                }
                AndroidCardCommand.SINGLE_TAP -> {
                    if (settings.getTapSingle() == Constants.TapMode.REVEAL) {
                        if (!establishPinIfNeeded(position) && !toggleComposeReveal(position, entry)) return false
                    } else {
                        cardActions.onCardSingleClicked(position, "")
                    }
                }
                AndroidCardCommand.DOUBLE_TAP -> {
                    if (settings.getTapDouble() == Constants.TapMode.REVEAL) {
                        if (!establishPinIfNeeded(position) && !toggleComposeReveal(position, entry)) return false
                    } else {
                        cardActions.onCardDoubleClicked(position, "")
                    }
                }
            }
            return true
        }
    }

    fun getEntries(): ArrayList<Entry> = entries.getEntries()

    fun hasIssuer(issuer: String?): Boolean = entries.hasIssuer(issuer)

    fun saveAndRefresh(auto_backup: Boolean) {
        saveAndRefresh(auto_backup, NO_POSITION)
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
    override fun addEntry(e: Entry) {
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

    /**
     * Replaces [existing] with [replacement], preserving the vault order and list id, and saves.
     * The replacement is validated and checked for duplicates (excluding the entry being replaced).
     * On any failure the replacement is destroyed and the vault is left unchanged.
     */
    override fun replaceEntry(existing: Entry, replacement: Entry): Boolean =
        replaceEntry(existing, replacement, preserveMissingPin = true)

    private fun replaceEntry(existing: Entry, replacement: Entry, preserveMissingPin: Boolean): Boolean {
        synchronized(VaultSession.MutationLock) {
            val key = encryptionKey
            if (key == null || VaultSession.getKey() !== key) {
                replacement.destroy()
                return false
            }
            if (!existing.areDetailsVisible()) {
                replacement.destroy()
                return false
            }
            val existingIndex = entries.indexOf(existing)
            if (existingIndex < 0) {
                replacement.destroy()
                return false
            }
            try {
                replacement.validate()
            } catch (_: RuntimeException) {
                replacement.destroy()
                return false
            }
            val proposed = entries.getEntries()
            if (proposed.indices.any { it != existingIndex && proposed[it] == replacement }) {
                replacement.destroy()
                return false
            }
            // Preserve the metadata and reveal state the form does not edit, like the desktop does.
            val wasRevealed = composeRevealed(existing)
            val oldVisibilityKey = composeVisibilityKey(existing)
            replacement.setDetailsVisible(existing.areDetailsVisible())
            replacement.setLastUsed(existing.getLastUsed())
            replacement.setUsedFrequency(existing.getUsedFrequency())
            if (preserveMissingPin && replacement.getType() == Entry.OTPType.MOTP && replacement.getPin().isEmpty()
                && existing.getPin().isNotEmpty()) {
                replacement.setPin(existing.getPin())
            }
            proposed[existingIndex] = replacement
            val result = persistence.save(proposed, key, settings.getAutoBackupEncryptedPasswordsEnabled(),
                UiBasedBackgroundTask.OutcomeCallback { outcome -> handleBackupOutcome(outcome) })
            if (result !is VaultWriteResult.Saved) {
                replacement.destroy()
                if (result is VaultWriteResult.RecoveryRequired) loadEntries()
                return false
            }
            val old = entries.getEntry(existingIndex)
            if (!entries.replaceEntry(existingIndex, replacement)) {
                replacement.destroy()
                loadEntries()
                return false
            }
            composeRevealOverrides.remove(old)
            composeVisibilityKeys.remove(old)
            composeRevealOverrides[replacement] = wasRevealed
            replacement.setVisible(wasRevealed)
            persistComposeRevealKey(composeVisibilityKey(replacement), wasRevealed, obsoleteKey = oldVisibilityKey)
            old.destroy()
            updateTagsFilter()
            entriesChanged(presentation.indexOf(replacement))
            return true
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
                accepted.forEach { EntryUsage.markImported(it, now) }
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
                entriesChanged(NO_POSITION)
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

        if (itemPos == NO_POSITION)
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
        composeEditorView?.clearEntryEditor()
        composeEditTarget = null
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
        activeTagsDialog?.dismiss()
        activeTagsDialog = null
        activePopupMenu?.dismiss()
        activePopupMenu = null
    }

    /** Clears secrets held by adapters/views and invalidates outstanding filters/timers. */
    fun lockEntries() {
        encryptionKey = null
        composeRevealOverrides.clear()
        composeVisibilityKeys.clear()
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
            composeRevealOverrides.clear()
            composeVisibilityKeys.clear()
            taskHandler.removeCallbacksAndMessages(null)
            for (entry in entries.getEntries()) entry.destroy()
            entries.updateEntries(ArrayList(), false)
            entries.updateEntries(result.takeEntries(), true)
            clearFilter()
            entriesChanged(NO_POSITION)
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

    fun isEntryRevealed(entry: Entry): Boolean {
        synchronized(VaultSession.MutationLock) {
            return encryptionKey != null && VaultSession.getKey() === encryptionKey &&
                entries.getEntries().any { it === entry } && composeRevealed(entry)
        }
    }

    fun canExportPasswordArchive(entry: Entry, generation: Long): Boolean {
        synchronized(VaultSession.MutationLock) {
            return VaultSession.isCurrent(generation) && encryptionKey != null && VaultSession.getKey() === encryptionKey &&
                entries.getEntries().any { it === entry } && composeRevealed(entry) && entry.isPasswordArchive()
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

    /** Prepare cached token/color state for a renderer without retaining a ViewHolder. */
    fun prepareEntry(position: Int): Entry {
        val entry = presentation.get(position)
        if (!entry.isTimeBased() || entry.getCurrentOTP() == null) entry.updateOTP(false)
        if (settings.isHighlightTokenOptionEnabled() && (!settings.getTapToReveal() || entry.isVisible())) {
            entry.hasColorChanged()
        }
        return entry
    }

    val cardActions: EntryCardEvents = object : EntryCardEvents {
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
        if (selected.isPasswordArchive()) return
        if (selected.isEmail()) {
            chooseEmailPasswordToCopy(position, dropToBackground)
            return
        }
        if (selected.isSshKey()) {
            chooseSshKeyToCopy(position, dropToBackground)
            return
        }
        if (selected.isPassword() && selected.getPasswordFields().value("username").isNotEmpty() &&
            selected.getPasswordFields().value("password").isNotEmpty()
        ) {
            choosePasswordFieldToCopy(position, dropToBackground)
            return
        }
        val otp = if (selected.isPassword()) selected.getPasswordFields().value("password")
            else if (selected.isNote()) selected.getNote()
            else text.ifEmpty { selected.getCurrentOTP() ?: "" }
        if (otp.isEmpty()) return
        if (!recordCopyUse(selected)) {
            Toast.makeText(context, "Not copied: the vault could not be saved or the session changed.", Toast.LENGTH_LONG).show()
            return
        }
        Tools.copyToClipboard(context, otp)
        if (dropToBackground) {
            (context as? MainActivity)?.moveTaskToBack(true)
        }
    }

    /** Persist usage before publishing secrets, matching the desktop copy transaction. */
    private fun recordCopyUse(selected: Entry): Boolean {
        synchronized(VaultSession.MutationLock) {
            val key = encryptionKey ?: return false
            val generation = VaultSession.generation()
            if (VaultSession.getKey() !== key || entries.getEntries().none { it === selected }) return false
            val used = System.currentTimeMillis()
            val candidate = ArrayList<Entry>()
            try {
                entries.getEntries().forEach { entry ->
                    candidate.add(Entry(entry.toJSON()).also { if (entry === selected) EntryUsage.recordUse(it, used) })
                }
                val result = persistence.save(candidate, key, false,
                    UiBasedBackgroundTask.OutcomeCallback { outcome -> handleBackupOutcome(outcome) })
                if (result is VaultWriteResult.RecoveryRequired) { lockEntries(); return false }
                if (result !is VaultWriteResult.Saved || !VaultSession.isCurrent(generation) ||
                    VaultSession.getKey() !== key || entries.getEntries().none { it === selected }) return false
                EntryUsage.recordUse(selected, used)
                if (presentation.resortAfterUsage()) notifyDataSetChanged() else notifyEntryChanged(selected)
                return true
            } catch (_: Exception) { return false }
            finally { candidate.forEach { it.destroy() } }
        }
    }

    /** Which stored SSH field a copy action takes; never more than one at a time. */
    private enum class SshField { PUBLIC_KEY, PRIVATE_KEY, PASSWORD }

    /** Explicitly choose one field; do not retain key text in a pending dialog callback. */
    private fun chooseSshKeyToCopy(position: Int, dropToBackground: Boolean) {
        showActionDialog(position, BiConsumer { target, entry ->
            val keys = entry.getSshKeys()
            val connection = entry.getSshConnection()
            val choices = ArrayList<SshField>()
            val labels = ArrayList<String>()
            if (keys.publicKey.isNotBlank()) {
                choices.add(SshField.PUBLIC_KEY)
                labels.add(context.getString(R.string.ssh_public_key))
            }
            if (keys.privateKey.isNotBlank()) {
                choices.add(SshField.PRIVATE_KEY)
                labels.add(context.getString(R.string.ssh_private_key))
            }
            // The stored connection password, when there is one, is copied like a key: explicitly.
            if (!connection?.password.isNullOrEmpty()) {
                choices.add(SshField.PASSWORD)
                labels.add(context.getString(R.string.ssh_password))
            }
            activeSshCopyDialog?.dismiss()
            activeSshCopyDialog = null
            val activity = context as? Activity ?: return@BiConsumer
            val pending = object { var host: ComposeDialogHost? = null }
            val view = EntryCopyComposeView(
                activity = activity,
                title = context.getString(R.string.choose_ssh_key_to_copy),
                cancelLabel = context.getString(android.R.string.cancel),
                fields = labels,
                onSelect = { index ->
                    pending.host?.dismiss()
                    if (activeSshCopyDialog === pending.host) activeSshCopyDialog = null
                    if (index in choices.indices) withTarget(target, Consumer { actual ->
                        if (!actual.isSshKey()) return@Consumer
                        val current = actual.getSshKeys()
                        val value = when (choices[index]) {
                            SshField.PUBLIC_KEY -> current.publicKey
                            SshField.PRIVATE_KEY -> current.privateKey
                            SshField.PASSWORD -> actual.getSshConnection()?.password.orEmpty()
                        }
                        if (value.isBlank()) return@Consumer
                        val currentPos = presentation.indexOf(actual)
                        val currentIndex = entries.indexOf(actual)
                        if (!EntryIndexGuard.isValid(currentIndex, entries.getEntries().size)) return@Consumer
                        Tools.copyToClipboard(context, value)
                        updateLastUsedAndFrequency(currentPos, currentIndex)
                        if (dropToBackground) (context as? MainActivity)?.moveTaskToBack(true)
                    })
                },
                onDismiss = {
                    pending.host?.dismiss()
                    if (activeSshCopyDialog === pending.host) activeSshCopyDialog = null
                }
            )
            pending.host = view
            activeSshCopyDialog = view
            view.show()
        })
    }

    private fun copyAccountValue(selected: Entry, dropToBackground: Boolean): Boolean {
        val value = selected.accountToCopy()
        if (value.isEmpty()) return false
        if (!recordCopyUse(selected)) {
            Toast.makeText(context, "Not copied: the vault could not be saved or the session changed.", Toast.LENGTH_LONG).show()
            return false
        }
        Tools.copyToClipboard(context, value)
        if (dropToBackground) (context as? MainActivity)?.moveTaskToBack(true)
        return true
    }

    private fun copyPasswordValue(position: Int, selected: Entry, dropToBackground: Boolean): Boolean {
        val choices = selected.passwordChoicesToCopy()
        if (choices.isEmpty()) return false
        if (choices.map { it.second }.distinct().size == 1) {
            if (!recordCopyUse(selected)) {
                Toast.makeText(context, "Not copied: the vault could not be saved or the session changed.", Toast.LENGTH_LONG).show()
                return false
            }
            Tools.copyToClipboard(context, choices.first().second)
            if (dropToBackground) (context as? MainActivity)?.moveTaskToBack(true)
            return true
        }
        showActionDialog(position, BiConsumer { target, entry ->
            val currentChoices = entry.passwordChoicesToCopy()
            if (currentChoices.isEmpty()) return@BiConsumer
            activeSshCopyDialog?.dismiss()
            activeSshCopyDialog = null
            val activity = context as? Activity ?: return@BiConsumer
            val pending = object { var host: ComposeDialogHost? = null }
            val view = EntryCopyComposeView(
                activity = activity,
                title = "Choose password to copy",
                cancelLabel = context.getString(android.R.string.cancel),
                fields = currentChoices.map { it.first },
                onSelect = { index ->
                    pending.host?.dismiss()
                    if (activeSshCopyDialog === pending.host) activeSshCopyDialog = null
                    withTarget(target, Consumer { actual ->
                        val value = actual.passwordChoicesToCopy().getOrNull(index)?.second.orEmpty()
                        if (value.isEmpty()) return@Consumer
                        if (!recordCopyUse(actual)) {
                            Toast.makeText(context, "Not copied: the vault could not be saved or the session changed.", Toast.LENGTH_LONG).show()
                            return@Consumer
                        }
                        Tools.copyToClipboard(context, value)
                        if (dropToBackground) (context as? MainActivity)?.moveTaskToBack(true)
                    })
                },
                onDismiss = {
                    pending.host?.dismiss()
                    if (activeSshCopyDialog === pending.host) activeSshCopyDialog = null
                }
            )
            pending.host = view
            activeSshCopyDialog = view
            view.show()
        })
        return true
    }

    private enum class EmailCopyField { INCOMING_PASSWORD, OUTGOING_PASSWORD }

    private fun chooseEmailPasswordToCopy(position: Int, dropToBackground: Boolean) {
        showActionDialog(position, BiConsumer { target, entry ->
            val emailAccount = entry.getEmailAccount()
            val choices = ArrayList<EmailCopyField>()
            val labels = ArrayList<String>()
            if (emailAccount.account.optString("password").orEmpty().isNotEmpty()) {
                choices.add(EmailCopyField.INCOMING_PASSWORD)
                labels.add("Incoming password")
            }
            if (emailAccount.identity.optString("password").orEmpty().isNotEmpty()) {
                choices.add(EmailCopyField.OUTGOING_PASSWORD)
                labels.add("Outgoing password")
            }
            if (choices.isEmpty()) return@BiConsumer
            activeSshCopyDialog?.dismiss()
            activeSshCopyDialog = null
            val activity = context as? Activity ?: return@BiConsumer
            val pending = object { var host: ComposeDialogHost? = null }
            val view = EntryCopyComposeView(
                activity = activity,
                title = "Choose field to copy",
                cancelLabel = context.getString(android.R.string.cancel),
                fields = labels,
                onSelect = { index ->
                    pending.host?.dismiss()
                    if (activeSshCopyDialog === pending.host) activeSshCopyDialog = null
                    if (index in choices.indices) withTarget(target, Consumer { actual ->
                        if (!actual.isEmail()) return@Consumer
                        val currentAccount = actual.getEmailAccount()
                        val value = when (choices[index]) {
                            EmailCopyField.INCOMING_PASSWORD -> currentAccount.account.optString("password").orEmpty()
                            EmailCopyField.OUTGOING_PASSWORD -> currentAccount.identity.optString("password").orEmpty()
                        }
                        if (value.isEmpty()) return@Consumer
                        if (!recordCopyUse(actual)) {
                            Toast.makeText(context, "Not copied: the vault could not be saved or the session changed.", Toast.LENGTH_LONG).show()
                            return@Consumer
                        }
                        Tools.copyToClipboard(context, value)
                        if (dropToBackground) (context as? MainActivity)?.moveTaskToBack(true)
                    })
                },
                onDismiss = {
                    pending.host?.dismiss()
                    if (activeSshCopyDialog === pending.host) activeSshCopyDialog = null
                }
            )
            pending.host = view
            activeSshCopyDialog = view
            view.show()
        })
    }

    private enum class PasswordCopyField { PASSWORD, USERNAME }

    private fun choosePasswordFieldToCopy(position: Int, dropToBackground: Boolean) {
        showActionDialog(position, BiConsumer { target, entry ->
            val fields = entry.getPasswordFields()
            val choices = ArrayList<PasswordCopyField>()
            val labels = ArrayList<String>()
            if (fields.value("password").isNotEmpty()) {
                choices.add(PasswordCopyField.PASSWORD)
                labels.add("Password")
            }
            if (fields.value("username").isNotEmpty()) {
                choices.add(PasswordCopyField.USERNAME)
                labels.add("Username")
            }
            if (choices.isEmpty()) return@BiConsumer
            activeSshCopyDialog?.dismiss()
            activeSshCopyDialog = null
            val activity = context as? Activity ?: return@BiConsumer
            val pending = object { var host: ComposeDialogHost? = null }
            val view = EntryCopyComposeView(
                activity = activity,
                title = "Choose field to copy",
                cancelLabel = context.getString(android.R.string.cancel),
                fields = labels,
                onSelect = { index ->
                    pending.host?.dismiss()
                    if (activeSshCopyDialog === pending.host) activeSshCopyDialog = null
                    if (index in choices.indices) withTarget(target, Consumer { actual ->
                        if (!actual.isPassword()) return@Consumer
                        val currentFields = actual.getPasswordFields()
                        val value = when (choices[index]) {
                            PasswordCopyField.PASSWORD -> currentFields.value("password")
                            PasswordCopyField.USERNAME -> currentFields.value("username")
                        }
                        if (value.isEmpty()) return@Consumer
                        if (!recordCopyUse(actual)) {
                            Toast.makeText(context, "Not copied: the vault could not be saved or the session changed.", Toast.LENGTH_LONG).show()
                            return@Consumer
                        }
                        Tools.copyToClipboard(context, value)
                        if (dropToBackground) (context as? MainActivity)?.moveTaskToBack(true)
                    })
                },
                onDismiss = {
                    pending.host?.dismiss()
                    if (activeSshCopyDialog === pending.host) activeSshCopyDialog = null
                }
            )
            pending.host = view
            activeSshCopyDialog = view
            view.show()
        })
    }

    private fun cardTapToRevealHandler(position: Int) {
        if (!validDisplayedPosition(position)) return
        val entry = presentation.get(position)
        val realIndex = entries.indexOf(entry)
        if (!EntryIndexGuard.isValid(realIndex, entries.getEntries().size)) return
        val realEntry = entries.getEntry(realIndex)
        // A configured legacy tap deliberately resumes its own timed-reveal policy.
        composeRevealOverrides.remove(entry)

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
        // The one-step advance and its ceiling are shared with the desktop counter action.
        if (!EntryUsage.advanceHotp(entry)) return
        notifyItemChanged(position)

        realEntry.setCounter(entry.getCounter())
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

        if (safePosition != null) {
            // The stamping rule is shared with the desktop copy action, so the counters agree.
            EntryUsage.recordUse(presentation.get(safePosition), timeStamp)
        }

        EntryUsage.recordUse(realEntry, timeStamp)
        saveEntries(false)

        if (presentation.resortAfterUsage()) {
            notifyDataSetChanged()
            return false
        }

        return true
    }

    /** Swaps two entries within the selected tab, only in the unsorted, unfiltered view. */
    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
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

    fun removeItem(position: Int, requireRevealed: Boolean = false) {
        showActionDialog(position, BiConsumer { target, entry ->
            // Name the entry the way the desktop and iOS prompts do: the label, or the issuer
            // when the label is blank, rather than an empty pair of quotes.
            dialogs.delete(entry.privacyTitle(), Runnable {
                withTarget(target, Consumer { actual ->
                    if (requireRevealed && !composeRevealed(actual)) return@Consumer
                    val index = entries.indexOf(actual)
                    val displayedIndex = presentation.indexOf(actual)
                    if (displayedIndex >= 0) {
                        presentation.removeDisplayed(displayedIndex)
                        notifyItemRemoved(displayedIndex)
                    }
                    entries.removeEntry(index)
                    composeRevealOverrides.remove(actual)
                    removePersistedComposeReveal(actual)
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
        if (!validDisplayedPosition(pos) || !presentation.get(pos).hasOtpUri()) return
        val uri: Uri? = Uri.parse(presentation.get(pos).toUriString())
        if (uri != null) {
            val bitmap: Bitmap
            try {
                bitmap = BarcodeEncoder().encodeBitmap(uri.toString(), BarcodeFormat.QR_CODE, 0, 0)
            } catch (ignored: Exception) {
                Toast.makeText(context, R.string.toast_qr_failed_to_generate, Toast.LENGTH_LONG).show()
                return
            }
            val activity = context as? Activity ?: return
            activeQrDialog?.dismiss()
            var host: QrCodeComposeView? = null
            val closeHost = {
                val h = host
                if (activeQrDialog === h) activeQrDialog = null
                h?.dismiss()
                Unit
            }
            host = QrCodeComposeView(
                activity = activity,
                title = context.getString(R.string.dialog_title_qr_code),
                bitmap = bitmap,
                confirmLabel = context.getString(android.R.string.ok),
                onDismiss = closeHost
            )
            activeQrDialog = host
            host.show()
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
        val menuItemView = view.findViewById<View>(R.id.menuButton) ?: view
        val popup = PopupMenu(view.context, menuItemView)
        val inflate: MenuInflater = popup.getMenuInflater()
        inflate.inflate(R.menu.menu_popup, popup.getMenu())

        if (!popupEntry.hasOtpUri()) {
            popup.getMenu().findItem(R.id.menu_popup_show_qr_code)?.setVisible(false)
            popup.getMenu().findItem(R.id.menu_send_keystrokes)?.setVisible(false)
        }
        if (popupEntry.isPassword() || popupEntry.isPasswordArchive())
            popup.getMenu().findItem(R.id.menu_popup_edit)?.setVisible(false)
        if (popupEntry.isPassword())
            popup.getMenu().findItem(R.id.menu_popup_password_details)?.setVisible(true)
        if (com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput.supportsAny(popupEntry)) {
            popup.getMenu().findItem(R.id.menu_popup_edit_password)?.setVisible(true)
        }
        if (popupEntry.isEmail())
            popup.getMenu().findItem(R.id.menu_popup_email_details)?.setVisible(true)
        if (!composeRevealed(popupEntry)) {
            popup.getMenu().findItem(R.id.menu_popup_edit)?.setVisible(false)
            popup.getMenu().findItem(R.id.menu_popup_edit_password)?.setVisible(false)
            popup.getMenu().findItem(R.id.menu_popup_show_qr_code)?.setVisible(false)
            popup.getMenu().findItem(R.id.menu_popup_password_details)?.setVisible(false)
            popup.getMenu().findItem(R.id.menu_popup_email_details)?.setVisible(false)
        }
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
                    openEmailDetailsDialog(popupTarget, actual)
                })
                true
            } else if (id == R.id.menu_popup_password_details) {
                withTarget(popupTarget, Consumer { actual ->
                    openPasswordDetailsDialog(actual)
                })
                true
            } else if (id == R.id.menu_popup_edit_password) {
                withTarget(popupTarget, Consumer { actual ->
                    openPasswordEditor(popupTarget, actual)
                })
                true
            } else if (id == R.id.menu_popup_edit) {
                withTarget(popupTarget, Consumer { actual ->
                    if (actual.isEmail()) { openEmailEditor(popupTarget, actual); return@Consumer }
                    if (com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput.supportsAny(actual)) {
                        openPasswordEditor(popupTarget, actual)
                        return@Consumer
                    }
                    if (actual.isNote() || actual.isSshKey() || actual.getType() in com.fairuniversal.offlineotp.ui.vault.ManualEntryInput.otpTypes) {
                        openSharedEditor(popupTarget, actual)
                    }
                })
                true
            } else if (id == R.id.menu_popup_tags) {
                withTarget(popupTarget, Consumer { actual ->
                    openTagsDialog(popupTarget, actual)
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
                if (composeRevealed(popupEntry)) showQRCode(presentation.indexOf(popupEntry))
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
        entriesChanged(NO_POSITION)
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

    /** In-use tags in the shared display order (see EntryTagFilter.sortedNames). */
    override fun getTags(): List<String> = entries.getSortedTags()

    /** Per-tag entry counts for the drawer rows (#994). */
    override fun getTagCounts(): Map<String, Int> = entries.getTagCounts()

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

    interface Changes {
        fun changed()
        fun itemChanged(position: Int)
        fun itemMoved(from: Int, to: Int)
        fun itemRemoved(position: Int)
    }

    fun notifyDataSetChanged() = changes.changed()
    private fun notifyItemChanged(position: Int) = changes.itemChanged(position)
    private fun notifyItemMoved(from: Int, to: Int) = changes.itemMoved(from, to)
    private fun notifyItemRemoved(position: Int) = changes.itemRemoved(position)

    private companion object {
        const val NO_POSITION = -1
        const val PREF_COMPOSE_HIDDEN_ENTRIES = "pref_compose_hidden_entries"
        const val PREF_COMPOSE_REVEALED_ENTRIES = "pref_compose_revealed_entries"
    }

    /** Lets the activity react to drag/drop events (pausing the token updater). */
    interface Callback {
        fun onMoveEventStart()
        fun onMoveEventStop()
    }
}
