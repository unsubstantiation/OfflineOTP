package com.fairuniversal.offlineotp.desktop.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.fairuniversal.offlineotp.desktop.control.DesktopSessionLifetime
import com.fairuniversal.offlineotp.desktop.control.DesktopEntryCreation
import com.fairuniversal.offlineotp.desktop.control.DesktopEntryEditing
import com.fairuniversal.offlineotp.desktop.control.EntryEditor
import com.fairuniversal.offlineotp.desktop.control.DesktopUnlockOperation
import com.fairuniversal.offlineotp.desktop.control.DesktopEntryCopy
import com.fairuniversal.offlineotp.desktop.control.ExpiringClipboard
import com.fairuniversal.offlineotp.ui.vault.EntryCopyDialog
import com.fairuniversal.offlineotp.ui.vault.EntryConfirmationDialog
import com.fairuniversal.offlineotp.ui.vault.VaultEntryKind
import com.fairuniversal.offlineotp.ui.vault.ManualEntryDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.fairuniversal.offlineotp.desktop.ui.IssuerIcons
import com.fairuniversal.offlineotp.ui.vault.VaultArtwork
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

import com.fairuniversal.offlineotp.View.EntryListPresentation
import com.fairuniversal.offlineotp.desktop.control.DesktopVaultState
import com.fairuniversal.offlineotp.desktop.control.VaultController
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import com.fairuniversal.offlineotp.ui.unlock.UnlockScreen
import com.fairuniversal.offlineotp.ui.vault.VaultDialogs
import com.fairuniversal.offlineotp.ui.vault.VaultDialogKind
import com.fairuniversal.offlineotp.ui.vault.VaultEntryDialog
import com.fairuniversal.offlineotp.ui.vault.VaultClock
import com.fairuniversal.offlineotp.ui.vault.VaultPresenter
import com.fairuniversal.offlineotp.ui.vault.VaultFilter
import com.fairuniversal.offlineotp.ui.vault.VaultFilterCombination
import com.fairuniversal.offlineotp.ui.vault.VaultProjection
import com.fairuniversal.offlineotp.ui.vault.VaultScreen
import com.fairuniversal.offlineotp.ui.vault.VaultStrings
import com.fairuniversal.offlineotp.ui.vault.rememberNowMillis

import java.util.concurrent.Executors
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.AWTEvent
import java.awt.Component
import java.awt.event.AWTEventListener

/**
 * Compose Desktop entry point: the shared `:ui` screen over the existing portable vault.
 *
 * This is the migration-stage host, not a replacement for the Swing client yet. It deliberately
 * contains no presentation logic of its own: the frame comes from [VaultProjection], the widgets
 * come from `:ui`, and unlocking/saving go through the same [VaultController] and
 * [DesktopVaultState] the Swing window uses, so both desktop clients read and write one vault.
 *
 * What this host does not have yet (tracked in docs/compose-unified-ui-plan.md): structured-credential editing, backup/restore, hardware keys, preferences. Idle auto-lock now uses the saved desktop timeout. The shared screen
 * hides the entry-actions affordance until a host supplies it, so nothing here promises an action
 * that silently does nothing.
 */
fun main() {
    val directory = DesktopStorage.defaultDirectory()
    val controller = try {
        VaultController(DesktopStorage.open(directory))
    } catch (error: Exception) {
        System.err.println(
            "OfflineOTP could not open its vault directory: ${error.message ?: error.javaClass.simpleName}"
        )
        System.exit(1)
        return
    }
    application {
        ComposeVaultApp(controller)
    }
}

@Composable
private fun ApplicationScope.ComposeVaultApp(controller: VaultController) {
    var session by remember { mutableStateOf<DesktopVaultState?>(null) }
    var closing by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            closing = true
            session?.lock()
            session = null
            exitApplication()
        },
        title = "OfflineOTP",
        state = rememberWindowState(width = 520.dp, height = 820.dp)
    ) {
        ForestTheme {
            val vault = session
            if (vault == null) {
                DesktopUnlockContent(controller) { loaded ->
                    if (closing) DesktopUnlockOperation.discard(loaded)
                    else session = DesktopVaultState(loaded.key).apply { load(loaded.entries) }
                }
            } else {
                VaultContent(
                    controller = controller,
                    vault = vault,
                    window = window,
                    onLock = {
                        vault.lock()
                        session = null
                    }
                )
            }
        }
    }
}

/** Each locked screen gets a fresh operation; disposed-screen callbacks are terminal. */
@Composable
private fun DesktopUnlockContent(controller: VaultController, onLoaded: (VaultController.Session.Loaded) -> Unit) {
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val worker = remember(controller) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "OfflineOTP unlock").apply { isDaemon = true } }
    }
    val operation = remember(controller) {
        DesktopUnlockOperation(worker, { task -> EventQueue.invokeLater(task) },
            unlock = { password ->
                if (controller.isInitialized()) controller.openVault(password) else controller.createVault(password)
            },
            onBusy = { busy = it },
            onResult = { result -> when (result) {
                is VaultController.Session.Loaded -> onLoaded(result)
                is VaultController.Session.Error -> failure = failureText(result.failure)
            } })
    }
    DisposableEffect(operation) {
        onDispose { operation.close(); worker.shutdown() }
    }
    UnlockScreen(strings = VaultStrings.English, error = failure, busy = busy,
        hint = if (controller.isInitialized()) null else "There is no vault in this folder yet. Choose a password to create one.",
        onSubmit = { password -> failure = null; operation.submit(password); Unit })
}

@Composable
private fun VaultContent(controller: VaultController, vault: DesktopVaultState, window: java.awt.Window, onLock: () -> Unit) {
    val strings = VaultStrings.English
    val initialFilter = remember(vault) {
        VaultFilter(
            sortMode = controller.savedSortMode(),
            category = EntryListPresentation.Category.OTP,
            // The desktop client has always let a search and a tag selection narrow the list
            // together; the projection carries that policy as a value.
            combination = VaultFilterCombination.SEARCH_AND_TAGS
        )
    }
    var revision by remember(vault) { mutableStateOf(0) }
    val scheduler = remember(vault) { AwtScheduler() }
    val clipboard = remember(vault) { ExpiringClipboard(scheduler, AwtClipboard) }
    val copier = remember(vault) { DesktopEntryCopy(vault, EntryEditor(controller, vault), clipboard) }
    var copyNotice by remember(vault) { mutableStateOf<String?>(null) }
    val creation = remember(vault) { DesktopEntryCreation(vault, EntryEditor(controller, vault)::add) }
    val editing = remember(vault) { DesktopEntryEditing(vault, EntryEditor(controller, vault)::replace) }
    val dialogs = remember(vault) { VaultDialogs { revision++ } }
    val presenter = remember(vault) {
        VaultPresenter(
            projection = VaultProjection(vault.entryList, vault.presentation),
            initialFilter = initialFilter,
            addEntry = { copier.pending?.let { copier.dismiss(it) }; copyNotice = null; editing.pending?.let { editing.dismiss(it) }; dialogs.pending?.let { dialogs.dismiss(it) }; creation.open(); revision++ },
            editEntry = { card ->
                copier.pending?.let { copier.dismiss(it) }; copyNotice = null
                creation.pending?.let { creation.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                editing.open(card.id)
                revision++
            },
            advanceCounter = { card -> copier.pending?.let { copier.dismiss(it) }; copyNotice = null; editing.pending?.let { editing.dismiss(it) }; creation.pending?.let { creation.dismiss(it) }; dialogs.open(VaultDialogKind.COUNTER, card.id) },
            enterPin = { card -> copier.pending?.let { copier.dismiss(it) }; copyNotice = null; editing.pending?.let { editing.dismiss(it) }; creation.pending?.let { creation.dismiss(it) }; dialogs.open(VaultDialogKind.PIN, card.id) },
            deleteEntry = { card -> copier.pending?.let { copier.dismiss(it) }; copyNotice = null; editing.pending?.let { editing.dismiss(it) }; creation.pending?.let { creation.dismiss(it) }; dialogs.open(VaultDialogKind.DELETE, card.id) },
            sortSelected = { mode -> controller.saveSortMode(mode) },
            lockVault = {
                copier.close()
                copyNotice = null
                editing.close()
                creation.close()
                dialogs.close()
                onLock()
            },
            onChange = { revision++ }
        )
    }
    DisposableEffect(presenter) {
        val lifetime = DesktopSessionLifetime(scheduler, controller.savedInactivityLockSeconds()) {
            presenter.lock() // Invalidates callbacks and dialogs before destroying the owned vault.
        }
        val listener = AWTEventListener { event ->
            var source = event.source as? Component
            while (source != null && source !== window) {
                source = if (source is java.awt.Window) source.owner else source.parent
            }
            if (source === window) lifetime.activity()
        }
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.addAWTEventListener(listener,
            AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or
                AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        onDispose {
            toolkit.removeAWTEventListener(listener)
            try { lifetime.close() } finally { scheduler.close() }
        }
    }
    val clock = remember(vault) { VaultClock(System::currentTimeMillis) }
    val now = rememberNowMillis(clock)
    val artwork = remember(vault) {
        VaultArtwork { name -> IssuerIcons.artwork(name)?.toComposeImageBitmap() }
    }
    val state = remember(now, revision, presenter) {
        val frame = presenter.frame(now)
        frame.copy(cards = frame.cards.map { card ->
            card.copy(copyable = card.revealed && (card.copyable || card.kind in
                listOf(VaultEntryKind.NOTES, VaultEntryKind.SSH_KEYS, VaultEntryKind.PASSWORDS)))
        })
    }
    // Bind exact row instances even when a newly generated frame is structurally equal to the last.
    val copyFrame = copier.bind(state.cards)
    fun performCopy(request: DesktopEntryCopy.Request, field: EntryEditor.CopyField) {
        copyNotice = when (copier.copy(request, field, presenter.isRevealed(request.entryId))) {
            DesktopEntryCopy.Result.COPIED -> null
            DesktopEntryCopy.Result.UNAVAILABLE -> "No value is available, or the entry is no longer revealed."
            DesktopEntryCopy.Result.SAVE_FAILED -> "Nothing was copied: the vault could not be saved."
            DesktopEntryCopy.Result.CLIPBOARD_FAILED -> "The system clipboard is unavailable."
        }
        presenter.applyFilter(presenter.filter)
        revision++
    }

    VaultScreen(
        state = state,
        strings = strings,
        actions = presenter.actions(copyEntry = { card ->
            creation.pending?.let { creation.dismiss(it) }
            editing.pending?.let { editing.dismiss(it) }
            dialogs.pending?.let { dialogs.dismiss(it) }
            copyNotice = null
            val request = copier.open(copyFrame, card, presenter.isRevealed(card.id))
            if (request == null) copyNotice = "No copyable value is available for this entry."
            else if (request.fields.size == 1 && card.kind != VaultEntryKind.SSH_KEYS) performCopy(request, request.fields.single())
            revision++
        }),
        appearance = presenter.appearance,
        artwork = artwork
    )
    val copyRequest = copier.pending
    if (copyRequest != null) EntryCopyDialog(
        fields = copyRequest.fields.map { field -> when (field) {
            EntryEditor.CopyField.TOKEN_OR_NOTE -> "Code or note"
            EntryEditor.CopyField.PASSWORD -> "Password"
            EntryEditor.CopyField.SSH_PUBLIC_KEY -> "Public key"
            EntryEditor.CopyField.SSH_PRIVATE_KEY -> "Private key"
            EntryEditor.CopyField.SSH_PASSWORD -> "Connection password"
        } },
        onSelect = { index -> copyRequest.fields.getOrNull(index)?.let { performCopy(copyRequest, it) } },
        onDismiss = { copier.dismiss(copyRequest); revision++ })
    copyNotice?.let { message -> EntryConfirmationDialog(title = "Copy", message = message,
        confirmLabel = "OK", dismissLabel = "Close", onConfirm = { copyNotice = null }, onDismiss = { copyNotice = null }) }
    val addRequest = creation.pending
    if (addRequest != null) {
        ManualEntryDialog(
            inputKey = addRequest, artwork = artwork,
            error = when (addRequest.failure) {
                DesktopEntryCreation.Failure.INVALID_INPUT -> "Invalid entry. Check the fields and re-enter secret fields."
                DesktopEntryCreation.Failure.SAVE_FAILED -> "Could not add the entry (duplicate or save failure). Re-enter secret fields to retry."
                null -> null
            },
            onDismiss = { creation.dismiss(addRequest); revision++ },
            onSubmit = { input ->
                if (creation.submit(addRequest, input)) presenter.applyFilter(presenter.filter)
                revision++
            }
        )
    }
    val editRequest = editing.pending
    if (editRequest != null) {
        val initial = remember(editRequest) { editing.initialInput(editRequest) }
        if (initial != null) ManualEntryDialog(
            inputKey = editRequest, initial = initial, editing = true, artwork = artwork,
            error = when (editRequest.failure) {
                DesktopEntryEditing.Failure.INVALID_INPUT -> "Invalid entry. Check the fields and re-enter any secret changes."
                DesktopEntryEditing.Failure.SAVE_FAILED -> "Could not save changes. The original entry was retained; re-enter any secret changes to retry."
                null -> null
            },
            onDismiss = { editing.dismiss(editRequest); revision++ },
            onSubmit = { input ->
                if (editing.submit(editRequest, input, presenter.isRevealed(editRequest.entryId))) {
                    presenter.entryRemoved(editRequest.entryId) // Discard prior reveal state for the replacement.
                }
                revision++
            }
        )
    }
    val request = dialogs.pending
    if (request != null) {
        VaultEntryDialog(
            request = request,
            onDismiss = { dialogs.dismiss(request) },
            onConfirm = {
                if (dialogs.isCurrent(request)) {
                    val saved = when (request.kind) {
                        VaultDialogKind.DELETE -> vault.deleteEntry(request.entryId) { remaining ->
                            controller.save(vault.key, remaining)
                        }
                        VaultDialogKind.COUNTER -> vault.advanceCounter(request.entryId) {
                            controller.save(vault.key, vault.snapshot())
                        }
                        VaultDialogKind.PIN -> false // PIN uses the separate input callback.
                    }
                    dialogs.complete(request, saved)
                    if (saved && request.kind == VaultDialogKind.DELETE) {
                        presenter.entryRemoved(request.entryId)
                    }
                }
            },
            onPinSubmit = { pin ->
                if (dialogs.isCurrent(request) && request.kind == VaultDialogKind.PIN) {
                    val saved = vault.submitPin(request.entryId, pin) {
                        controller.save(vault.key, vault.snapshot())
                    }
                    dialogs.complete(request, saved)
                }
            }
        )
    }
}

/** Wording for the controller's failure enum; the controller itself carries no UI strings. */
private fun failureText(failure: VaultController.Failure): String = when (failure) {
    VaultController.Failure.MISSING -> "No vault found in this folder."
    VaultController.Failure.AUTHENTICATION_FAILED -> "That password does not open this vault."
    VaultController.Failure.CORRUPT -> "The vault file is damaged."
    VaultController.Failure.IO_FAILURE -> "The vault file could not be read or written."
    VaultController.Failure.ALREADY_EXISTS -> "A vault already exists here."
    VaultController.Failure.INVALID_INPUT -> "The password was rejected."
}
