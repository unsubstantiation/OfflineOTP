package com.fairuniversal.offlineotp.desktop.compose

import com.fairuniversal.offlineotp.desktop.control.DesktopPasswordChange
import com.fairuniversal.offlineotp.desktop.control.DesktopPasswordChangeFlow
import com.fairuniversal.offlineotp.ui.vault.PasswordChangeDialog
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorageLocation
import com.fairuniversal.offlineotp.ui.vault.StorageLocationDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopHardwareUnlock
import com.fairuniversal.offlineotp.ui.unlock.HardwareUnlockDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopHardwareEnrollment
import com.fairuniversal.offlineotp.ui.unlock.HardwareEnrollmentDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopHardwareRemoval
import com.fairuniversal.offlineotp.desktop.control.DesktopHardwareCopy
import com.fairuniversal.offlineotp.ui.vault.HardwareCopyDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopHardwareOath
import com.fairuniversal.offlineotp.desktop.control.HardwareKeyManager
import com.fairuniversal.offlineotp.desktop.hardware.PcscHardwareDiscovery
import com.fairuniversal.offlineotp.desktop.storage.DesktopHardwareUnlockStore
import com.fairuniversal.offlineotp.ui.vault.HardwareOathDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.fairuniversal.offlineotp.desktop.control.DesktopQrImageRead
import com.fairuniversal.offlineotp.desktop.control.DesktopEmailDetails
import com.fairuniversal.offlineotp.ui.vault.EmailDetailsDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopPreferencesImport
import com.fairuniversal.offlineotp.ui.vault.PreferencesImportDialog
import com.fairuniversal.offlineotp.Utilities.PreferencesExport
import com.fairuniversal.offlineotp.desktop.control.DesktopArchiveExport
import com.fairuniversal.offlineotp.ui.vault.ArchiveExportDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopPasswordExport
import com.fairuniversal.offlineotp.ui.vault.PasswordExportDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopEmailExport
import com.fairuniversal.offlineotp.desktop.control.DesktopPreferencesExport
import com.fairuniversal.offlineotp.desktop.control.DesktopOpenPgpExport
import com.fairuniversal.offlineotp.ui.vault.OpenPgpExportDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopOpenPgpImport
import com.fairuniversal.offlineotp.ui.vault.OpenPgpImportDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopLinScpImport
import com.fairuniversal.offlineotp.ui.vault.LinScpImportDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopEmailImport
import com.fairuniversal.offlineotp.ui.vault.EmailImportDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopPasswordImport
import com.fairuniversal.offlineotp.ui.vault.PasswordImportDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopUriImport
import com.fairuniversal.offlineotp.ui.vault.UriImportDialog
import com.fairuniversal.offlineotp.desktop.control.BackupCoordinator
import com.fairuniversal.offlineotp.desktop.control.DesktopEncryptedRestore
import com.fairuniversal.offlineotp.ui.vault.EncryptedRestoreDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopLinScpExport
import com.fairuniversal.offlineotp.desktop.control.DesktopEncryptedExport
import com.fairuniversal.offlineotp.ui.vault.EncryptedExportDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopLayoutSettings
import com.fairuniversal.offlineotp.ui.vault.CardLayoutDialog
import com.fairuniversal.offlineotp.ui.vault.VaultAppearance
import com.fairuniversal.offlineotp.desktop.control.DesktopAutoLockSettings
import com.fairuniversal.offlineotp.ui.vault.AutoLockDialog
import com.fairuniversal.offlineotp.desktop.control.DesktopSessionLifetime
import com.fairuniversal.offlineotp.desktop.control.DesktopEntryCreation
import com.fairuniversal.offlineotp.desktop.control.DesktopEntryEditing
import com.fairuniversal.offlineotp.desktop.control.EntryEditor
import com.fairuniversal.offlineotp.desktop.control.DesktopUnlockOperation
import com.fairuniversal.offlineotp.desktop.control.DesktopEntryMove
import com.fairuniversal.offlineotp.desktop.control.DesktopEntryCopy
import com.fairuniversal.offlineotp.desktop.control.ExpiringClipboard
import com.fairuniversal.offlineotp.ui.vault.EntryCopyDialog
import com.fairuniversal.offlineotp.ui.vault.EntryConfirmationDialog
import com.fairuniversal.offlineotp.ui.vault.VaultEntryKind
import com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput
import com.fairuniversal.offlineotp.ui.vault.EmailSettingsDialog
import com.fairuniversal.offlineotp.ui.vault.EmailPasswordDialog
import com.fairuniversal.offlineotp.ui.vault.PasswordEntryDialog
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
 * derives rows through [VaultProjection] and adds host capability flags; widgets come from `:ui`,
 * and unlocking/saving go through the same [VaultController] and
 * [DesktopVaultState] the Swing window uses, so both desktop clients read and write one vault.
 *
 * What this host does not have yet (tracked in docs/compose-unified-ui-plan.md): broader email settings editing, hardware integrations, camera import, and the remaining preferences. Idle auto-lock now uses the saved desktop timeout. The shared screen
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
        ComposeVaultApp(controller, DesktopStorageLocation(directory))
    }
}

@Composable
private fun ApplicationScope.ComposeVaultApp(controller: VaultController, location: DesktopStorageLocation) {
    var session by remember { mutableStateOf<DesktopVaultState?>(null) }
    var closing by remember { mutableStateOf(false) }
    var enrollment by remember { mutableStateOf<DesktopHardwareEnrollment?>(null) }
    var enrollmentActive by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var rekeyRevision by remember { mutableStateOf(0) }
    val rekeyWorker = remember(controller) { Executors.newSingleThreadExecutor { task -> Thread(task, "OfflineOTP rekey") } }
    val rekey = remember(controller) { DesktopPasswordChangeFlow(rekeyWorker,
        { task -> EventQueue.invokeLater(task) }, controller::changePassword, { rekeyRevision++ }) }
    DisposableEffect(rekey) { onDispose { enrollment?.close(); rekey.close(); rekeyWorker.shutdown() } }


    Window(
        onCloseRequest = {
            if (rekey.canCloseWindow && enrollment?.busy != true) {
                closing = true
                enrollment?.close()
                rekey.close()
                session?.lock()
                session = null
                exitApplication()
            }
        },
        title = "OfflineOTP",
        state = rememberWindowState(width = 520.dp, height = 820.dp)
    ) {
        ForestTheme {
            @Suppress("UNUSED_VARIABLE") val observedRekeyRevision = rekeyRevision
            val vault = session
            if (vault == null && rekey.canUnlock && enrollment == null) {
                DesktopUnlockContent(controller, location) { loaded ->
                    if (closing) DesktopUnlockOperation.discard(loaded)
                    else session = DesktopVaultState(loaded.key).apply { load(loaded.entries) }
                }
            } else if (vault != null) {
                VaultContent(
                    controller = controller,
                    vault = vault,
                    window = window, location = location,
                    onChangePassword = { active, lock -> if (enrollment == null) rekey.open(vault, active, lock); Unit },
                    onEnrollHardware = { active, lock, hardwareWorker ->
                        if (enrollment == null && rekey.operation == null && active()) {
                            enrollmentActive = active
                            enrollment = DesktopHardwareEnrollment(vault, hardwareWorker, { task -> EventQueue.invokeLater(task) }, lock,
                                enroll = { pin, key, token ->
                                    val manager = HardwareKeyManager(PcscHardwareDiscovery(), DesktopHardwareUnlockStore(java.io.File(location.dataDirectory)))
                                    try { manager.enroll(pin, key, token) } finally { manager.close() }
                                }, changed = { rekeyRevision++ })
                        }
                    },
                    onLock = {
                        vault.lock()
                        session = null
                        rekey.sessionLocked()
                        if (enrollment?.status == DesktopHardwareEnrollment.Status.INPUT) {
                            enrollment?.close(); enrollment = null; enrollmentActive = null
                        }
                    }
                )
            }
            enrollment?.let { owner ->
                HardwareEnrollmentDialog(inputKey = owner, busy = owner.busy,
                    result = when (owner.status) {
                        DesktopHardwareEnrollment.Status.ENROLLED -> "Enrollment saved. Try hardware unlock; keep password access as a fallback."
                        DesktopHardwareEnrollment.Status.FAILED -> "Enrollment did not report success. Use password unlock and inspect hardware unlock before retrying. An authenticator credential or local record may already have been created."
                        else -> null
                    }, onSubmit = { pin ->
                        if (enrollment === owner && !closing) owner.submit(pin) { enrollmentActive?.invoke() == true }
                        else pin.fill(' ')
                    }, onDismiss = {
                        if (enrollment === owner && !owner.busy) { owner.close(); enrollment = null; enrollmentActive = null }
                    })
            }
            rekey.operation?.let { owner ->
                PasswordChangeDialog(inputKey = owner, busy = owner.status == DesktopPasswordChange.Status.WORKING,
                    invalid = owner.status == DesktopPasswordChange.Status.INVALID_INPUT,
                    result = when (owner.status) {
                        DesktopPasswordChange.Status.CHANGED -> "Password changed. Unlock with your new password."
                        DesktopPasswordChange.Status.FAILED -> "Password change did not report success. The vault remains locked. Try your current password, then the new password if necessary. Preserve the vault files if neither works; do not create a replacement vault."
                        else -> null
                    }, onSubmit = { current, next, confirmation -> rekey.submit(owner, current, next, confirmation) },
                    onDismiss = { rekey.dismiss(owner) })
            }
        }
    }
}

/** Each locked screen gets a fresh operation; disposed-screen callbacks are terminal. */
@Composable
private fun DesktopUnlockContent(controller: VaultController, location: DesktopStorageLocation, onLoaded: (VaultController.Session.Loaded) -> Unit) {
    var hardwareDialog by remember { mutableStateOf(false) }
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
    val hardwareUnlock = remember(controller) { DesktopHardwareUnlock(worker, { task -> EventQueue.invokeLater(task) },
        recover = { pin, token ->
            val manager = HardwareKeyManager(PcscHardwareDiscovery(), DesktopHardwareUnlockStore(java.io.File(location.dataDirectory)))
            try { manager.unlockWithKey(pin, token) } finally { manager.close() }
        }, open = controller::unlockWithKey, onBusy = { busy = it }, onResult = { result ->
            when (result) {
                is VaultController.Session.Loaded -> onLoaded(result)
                is VaultController.Session.Error -> failure = "Hardware unlock failed. Check the enrolled key, FIDO PIN, connection and touch. A missing, invalid or outdated enrollment also requires password unlock."
            }
        }) }
    DisposableEffect(operation) {
        onDispose { hardwareUnlock.close(); operation.close(); worker.shutdown() }
    }
    UnlockScreen(strings = VaultStrings.English, error = if (hardwareDialog) null else failure, busy = busy || hardwareDialog,
        onHardwareUnlock = if (controller.isInitialized()) ({ if (!busy) { failure = null; hardwareDialog = true } }) else null,
        hint = if (controller.isInitialized()) null else "There is no vault in this folder yet. Choose a password to create one.",
        onSubmit = { password -> if (!busy && !hardwareDialog) { failure = null; operation.submit(password) }; Unit })
    if (hardwareDialog) HardwareUnlockDialog(busy = busy, error = failure,
        onSubmit = { pin -> if (!busy) { failure = null; hardwareUnlock.submit(pin) } else pin.fill(' ') },
        onDismiss = { if (!busy) { hardwareDialog = false; failure = null } })
}

@Composable
private fun VaultContent(controller: VaultController, vault: DesktopVaultState, window: java.awt.Window, location: DesktopStorageLocation, onEnrollHardware: (() -> Boolean, () -> Unit, java.util.concurrent.Executor) -> Unit, onChangePassword: (() -> Boolean, () -> Unit) -> Unit, onLock: () -> Unit) {
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
    val exportWorker = remember(vault) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "OfflineOTP backup").apply { isDaemon = true } }
    }
    val exporter = remember(vault) { DesktopEncryptedExport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }) }
    val linScpExporter = remember(vault) { DesktopEncryptedExport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }, seal = DesktopLinScpExport::seal,
        snapshot = { DesktopLinScpExport.snapshot(vault) }) }
    val openPgpExporter = remember(vault) { DesktopOpenPgpExport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }) }
    val hardwareRemoval = remember(vault) { DesktopHardwareRemoval(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) },
        { DesktopHardwareUnlockStore(java.io.File(location.dataDirectory)).removeEnvelope() }, { revision++ }) }
    val hardwareCopy = remember(vault) { DesktopHardwareCopy(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }, copy = { pin, entry, token ->
            val manager = HardwareKeyManager(PcscHardwareDiscovery(), DesktopHardwareUnlockStore(java.io.File(location.dataDirectory)))
            try { manager.copyEntryToKey(pin?.takeIf { it.isNotEmpty() }, entry, requireTouch = true, token = token) } finally { manager.close() }
        }) }
    val hardwareOath = remember(vault) { DesktopHardwareOath(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ },
        list = { pin, token ->
            val manager = HardwareKeyManager(PcscHardwareDiscovery(), DesktopHardwareUnlockStore(java.io.File(location.dataDirectory)))
            try { manager.listOathAccounts(pin?.takeIf { it.isNotEmpty() }, token) } finally { manager.close() }
        }, calculate = { pin, deviceId, account, token ->
            val manager = HardwareKeyManager(PcscHardwareDiscovery(), DesktopHardwareUnlockStore(java.io.File(location.dataDirectory)))
            try { manager.calculateOath(pin?.takeIf { it.isNotEmpty() }, deviceId, account, token) } finally { manager.close() }
        }) }
    val archiveExporter = remember(vault) { DesktopArchiveExport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }) }
    val passwordExporter = remember(vault) { DesktopPasswordExport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }) }
    val emailExporter = remember(vault) { DesktopEmailExport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }) }
    val preferencesExporter = remember(vault) { DesktopPreferencesExport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, controller::exportPreferences, { revision++ }) }
    val preferencesImporter = remember(vault) { DesktopPreferencesImport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }, controller::importPreferences) }
    val restorer = remember(vault) { DesktopEncryptedRestore(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }, BackupCoordinator(controller, vault)::restoreEntries) }
    val passwordImporter = remember(vault) { DesktopPasswordImport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }, EntryEditor(controller, vault)::importEntries) }
    val emailImporter = remember(vault) { DesktopEmailImport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }, EntryEditor(controller, vault)::importEntries) }
    val linScpImporter = remember(vault) { DesktopLinScpImport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }, EntryEditor(controller, vault)::importEntries) }
    val openPgpImporter = remember(vault) { DesktopOpenPgpImport(vault, exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }, EntryEditor(controller, vault)::importEntries,
        replaceAll = { entries -> BackupCoordinator(controller, vault).restoreEntries(ArrayList(entries), replace = true) }) }
    val uriImporter = remember(vault) { DesktopUriImport(vault, BackupCoordinator(controller, vault)) }
    val imageReader = remember(vault) { DesktopQrImageRead(exportWorker,
        { task -> EventQueue.invokeLater(task) }, { revision++ }) }
    val scheduler = remember(vault) { AwtScheduler() }
    val clipboard = remember(vault) { ExpiringClipboard(scheduler, AwtClipboard) }
    val copier = remember(vault) { DesktopEntryCopy(vault, EntryEditor(controller, vault), clipboard) }
    val mover = remember(vault) { DesktopEntryMove(vault, EntryEditor(controller, vault)::move) }
    var showStorageLocation by remember(vault) { mutableStateOf(false) }
    var moveFailed by remember(vault) { mutableStateOf(false) }
    var copyNotice by remember(vault) { mutableStateOf<String?>(null) }
    val creation = remember(vault) { DesktopEntryCreation(vault, EntryEditor(controller, vault)::add) }
    val emailDetails = remember(vault) { DesktopEmailDetails(vault) }
    val editing = remember(vault) { DesktopEntryEditing(vault, EntryEditor(controller, vault)::replace) }
    val layoutSettings = remember(vault) { DesktopLayoutSettings(vault, controller::savedCardLayout, controller::trySaveCardLayout) }
    val autoLock = remember(vault) { DesktopAutoLockSettings(vault, controller::savedInactivityLockSeconds, controller::trySaveInactivityLockSeconds) }
    val dialogs = remember(vault) { VaultDialogs { revision++ } }
    val presenter = remember(vault) {
        VaultPresenter(
            projection = VaultProjection(vault.entryList, vault.presentation),
            initialFilter = initialFilter,
            appearance = VaultAppearance(cardLayout = controller.savedCardLayout()),
            addEntry = { autoLock.pending?.let { autoLock.dismiss(it) }; copier.pending?.let { copier.dismiss(it) }; copyNotice = null; emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }; dialogs.pending?.let { dialogs.dismiss(it) }; creation.open(); revision++ },
            editEntry = { card ->
                autoLock.pending?.let { autoLock.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }; copyNotice = null
                creation.pending?.let { creation.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }
                editing.open(card.id)
                revision++
            },
            advanceCounter = { card -> autoLock.pending?.let { autoLock.dismiss(it) }; copier.pending?.let { copier.dismiss(it) }; copyNotice = null; emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }; creation.pending?.let { creation.dismiss(it) }; dialogs.open(VaultDialogKind.COUNTER, card.id) },
            enterPin = { card -> autoLock.pending?.let { autoLock.dismiss(it) }; copier.pending?.let { copier.dismiss(it) }; copyNotice = null; emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }; creation.pending?.let { creation.dismiss(it) }; dialogs.open(VaultDialogKind.PIN, card.id) },
            deleteEntry = { card -> autoLock.pending?.let { autoLock.dismiss(it) }; copier.pending?.let { copier.dismiss(it) }; copyNotice = null; emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }; creation.pending?.let { creation.dismiss(it) }; dialogs.open(VaultDialogKind.DELETE, card.id) },
            sortSelected = { mode -> controller.saveSortMode(mode) },
            lockVault = {
                showStorageLocation = false
                emailDetails.close()
                openPgpImporter.close()
                linScpImporter.close()
                emailImporter.close()
                passwordImporter.close()
                imageReader.close()
                uriImporter.close()
                restorer.close()
                preferencesImporter.close()
                hardwareRemoval.close(); hardwareCopy.close(); hardwareOath.close(); archiveExporter.close(); passwordExporter.close(); emailExporter.close(); preferencesExporter.close()
                openPgpExporter.close()
                linScpExporter.close()
                exporter.close()
                layoutSettings.close()
                autoLock.close()
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
    val lifetime = remember(presenter) {
        DesktopSessionLifetime(scheduler, controller.savedInactivityLockSeconds(), startImmediately = false) {
            presenter.lock() // Invalidates callbacks and dialogs before destroying the owned vault.
        }
    }
    DisposableEffect(presenter) {
        lifetime.start()
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
            layoutSettings.close(); emailDetails.close(); openPgpImporter.close(); linScpImporter.close(); emailImporter.close(); passwordImporter.close(); imageReader.close(); uriImporter.close(); restorer.close(); preferencesImporter.close(); hardwareRemoval.close(); hardwareCopy.close(); hardwareOath.close(); archiveExporter.close(); passwordExporter.close(); emailExporter.close(); preferencesExporter.close(); openPgpExporter.close(); linScpExporter.close(); exporter.close()
            toolkit.removeAWTEventListener(listener)
            try { lifetime.close() } finally { scheduler.close(); exportWorker.shutdown() }
        }
    }
    val clock = remember(vault) { VaultClock(System::currentTimeMillis) }
    val now = rememberNowMillis(clock)
    val artwork = remember(vault) {
        VaultArtwork { name -> IssuerIcons.artwork(name)?.toComposeImageBitmap() }
    }
    val state = remember(now, revision, presenter) {
        val frame = presenter.frame(now)
        val liveEntries = vault.snapshot().associateBy { it.getListId() }
        frame.copy(cards = frame.cards.mapIndexed { index, card ->
            card.copy(canEditPassword = card.revealed && liveEntries[card.id]?.let {
                it.isPassword() && PasswordEntryInput.supports(it)
            } == true, canMoveUp = vault.presentation.canDrag() && index > 0,
                canMoveDown = vault.presentation.canDrag() && index < frame.cards.lastIndex,
                copyable = card.revealed && (card.copyable || card.kind in
                listOf(VaultEntryKind.NOTES, VaultEntryKind.SSH_KEYS, VaultEntryKind.PASSWORDS)))
        })
    }
    // Bind exact row instances even when a newly generated frame is structurally equal to the last.
    val copyFrame = copier.bind(state.cards)
    val moveFrame = mover.bind(state.cards)
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
            autoLock.pending?.let { autoLock.dismiss(it) }
            creation.pending?.let { creation.dismiss(it) }
            emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }
            dialogs.pending?.let { dialogs.dismiss(it) }
            copyNotice = null
            val request = copier.open(copyFrame, card, presenter.isRevealed(card.id))
            if (request == null) copyNotice = "No copyable value is available for this entry."
            else if (request.fields.size == 1 && card.kind != VaultEntryKind.SSH_KEYS) performCopy(request, request.fields.single())
            revision++
        }, moveEntry = { card, delta ->
            autoLock.pending?.let { autoLock.dismiss(it) }
            val moved = mover.move(moveFrame, card, delta, lifetime::checkActive)
            if (lifetime.checkActive() && !vault.isLocked) {
                moveFailed = !moved
                presenter.applyFilter(presenter.filter)
            }
            revision++
        }, configureAutoLock = {
            creation.pending?.let { creation.dismiss(it) }
            emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }
            copier.pending?.let { copier.dismiss(it) }
            dialogs.pending?.let { dialogs.dismiss(it) }
            copyNotice = null; moveFailed = false
            if (lifetime.checkActive()) autoLock.open()
            revision++
        }, exportEncrypted = {
            if (!vault.isLocked && exporter.pending == null && linScpExporter.pending == null && openPgpExporter.pending == null && restorer.pending == null && uriImporter.pending == null && passwordImporter.pending == null && emailImporter.pending == null && linScpImporter.pending == null && openPgpImporter.pending == null && lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                copyNotice = null; moveFailed = false
                try {
                    val target = DesktopSavePicker.encryptedBackup(window)
                    // Native modal pickers run a nested event loop: auto-lock may fire while open.
                    if (target != null && lifetime.checkActive() && !vault.isLocked) exporter.open(target)
                } catch (_: Exception) { copyNotice = "The backup destination could not be selected." }
                revision++
            }
        }, exportOpenPgp = {
            if (!vault.isLocked && exporter.pending == null && linScpExporter.pending == null && openPgpExporter.pending == null && restorer.pending == null && uriImporter.pending == null && passwordImporter.pending == null && emailImporter.pending == null && linScpImporter.pending == null && openPgpImporter.pending == null && lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                copyNotice = null; moveFailed = false
                try {
                    val target = DesktopSavePicker.openPgpExport(window)
                    // Native modal pickers run a nested event loop: auto-lock may fire while open.
                    if (target != null && lifetime.checkActive() && !vault.isLocked) openPgpExporter.open(target)
                } catch (_: Exception) { copyNotice = "The backup destination could not be selected." }
                revision++
            }
        }, exportLinScp = {
            if (!vault.isLocked && exporter.pending == null && linScpExporter.pending == null && openPgpExporter.pending == null && restorer.pending == null && uriImporter.pending == null && passwordImporter.pending == null && emailImporter.pending == null && linScpImporter.pending == null && openPgpImporter.pending == null && lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                copyNotice = null; moveFailed = false
                try {
                    val target = DesktopSavePicker.linScpExport(window)
                    // Native modal pickers run a nested event loop: auto-lock may fire while open.
                    if (target != null && lifetime.checkActive() && !vault.isLocked) linScpExporter.open(target)
                } catch (_: Exception) { copyNotice = "The backup destination could not be selected." }
                revision++
            }
        }, restoreEncrypted = {
            if (restorer.pending == null && exporter.pending == null && linScpExporter.pending == null && openPgpExporter.pending == null && uriImporter.pending == null && passwordImporter.pending == null && emailImporter.pending == null && linScpImporter.pending == null && openPgpImporter.pending == null && lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                copyNotice = null; moveFailed = false
                try {
                    val source = DesktopSavePicker.openEncryptedBackup(window)
                    if (source != null && lifetime.checkActive() && !vault.isLocked) restorer.open(source)
                } catch (_: Exception) { copyNotice = "The backup source could not be selected." }
                revision++
            }
        }, importUri = {
            if (restorer.pending == null && exporter.pending == null && linScpExporter.pending == null && openPgpExporter.pending == null && passwordImporter.pending == null && emailImporter.pending == null && linScpImporter.pending == null && openPgpImporter.pending == null && lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                copyNotice = null; moveFailed = false
                uriImporter.open(); revision++
            }
        }, importPasswords = {
            if (passwordImporter.pending == null && uriImporter.pending == null && restorer.pending == null && exporter.pending == null && linScpExporter.pending == null && openPgpExporter.pending == null && emailImporter.pending == null && linScpImporter.pending == null && openPgpImporter.pending == null && lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                copyNotice = null; moveFailed = false
                try {
                    val file = DesktopSavePicker.openPasswordFile(window)
                    if (file != null && lifetime.checkActive() && !vault.isLocked) passwordImporter.open(file)
                } catch (_: Exception) { copyNotice = "The password file could not be selected." }
                revision++
            }
        }, importEmail = {
            if (passwordImporter.pending == null && uriImporter.pending == null && restorer.pending == null && exporter.pending == null && linScpExporter.pending == null && openPgpExporter.pending == null && emailImporter.pending == null && linScpImporter.pending == null && openPgpImporter.pending == null && lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                copyNotice = null; moveFailed = false
                try {
                    val file = DesktopSavePicker.openEmailBackup(window)
                    if (file != null && lifetime.checkActive() && !vault.isLocked) emailImporter.open(file)
                } catch (_: Exception) { copyNotice = "The FairEmail backup could not be selected." }
                revision++
            }
        }, importLinScp = {
            if (passwordImporter.pending == null && uriImporter.pending == null && restorer.pending == null && exporter.pending == null && linScpExporter.pending == null && openPgpExporter.pending == null && emailImporter.pending == null && linScpImporter.pending == null && openPgpImporter.pending == null && lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                copyNotice = null; moveFailed = false
                try {
                    val file = DesktopSavePicker.openLinScpBackup(window)
                    if (file != null && lifetime.checkActive() && !vault.isLocked) linScpImporter.open(file)
                } catch (_: Exception) { copyNotice = "The LinSCP file could not be selected." }
                revision++
            }
        }, importOpenPgp = {
            if (passwordImporter.pending == null && uriImporter.pending == null && restorer.pending == null && exporter.pending == null && linScpExporter.pending == null && openPgpExporter.pending == null && emailImporter.pending == null && linScpImporter.pending == null && openPgpImporter.pending == null && lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }; editing.pending?.let { editing.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                copyNotice = null; moveFailed = false
                try {
                    val file = DesktopSavePicker.openOpenPgpBackup(window)
                    if (file != null && lifetime.checkActive() && !vault.isLocked) openPgpImporter.open(file)
                } catch (_: Exception) { copyNotice = "The OpenPGP backup could not be selected." }
                revision++
            }
        }, viewEmailDetails = { card ->
            if (lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                editing.pending?.let { editing.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                copyNotice = null; moveFailed = false
                emailDetails.open(card.id, presenter.isRevealed(card.id))
                revision++
            }
        }, configureLayout = {
            if (lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                editing.pending?.let { editing.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }
                copier.pending?.let { copier.dismiss(it) }
                dialogs.pending?.let { dialogs.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                copyNotice = null; moveFailed = false
                layoutSettings.open(); revision++
            }
        }, enrollHardwareUnlock = {
            if (lifetime.checkActive() && hardwareRemoval.pending == null && hardwareCopy.pending == null && hardwareOath.pending == null)
                onEnrollHardware(lifetime::checkActive, presenter::lock, exportWorker)
        }, removeHardwareUnlock = {
            if (lifetime.checkActive()) hardwareRemoval.open()
        }, hardwareCopy = {
            if (lifetime.checkActive() && hardwareOath.pending == null) hardwareCopy.open()
        }, hardwareOath = {
            if (lifetime.checkActive() && hardwareCopy.pending == null) hardwareOath.open()
        }, storageLocation = {
            if (lifetime.checkActive()) {
                creation.pending?.let { creation.dismiss(it) }
                editing.pending?.let { editing.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }
                showStorageLocation = true
            }
        }, changePassword = {
            if (lifetime.checkActive()) onChangePassword(lifetime::checkActive, presenter::lock)
        }, exportArchives = {
            if (lifetime.checkActive() && archiveExporter.pending == null) {
                creation.pending?.let { creation.dismiss(it) }
                editing.pending?.let { editing.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }
                if (!archiveExporter.open(lifetime::checkActive) && lifetime.checkActive()) copyNotice = "Could not prepare password archives for export."
            }
        }, exportPasswords = {
            if (lifetime.checkActive() && passwordExporter.pending == null) {
                creation.pending?.let { creation.dismiss(it) }
                editing.pending?.let { editing.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }
                if (!passwordExporter.open(lifetime::checkActive) && lifetime.checkActive()) copyNotice = "Could not prepare browser passwords for export."
            }
        }, exportEmail = {
            if (lifetime.checkActive() && emailExporter.pending == null) {
                creation.pending?.let { creation.dismiss(it) }
                editing.pending?.let { editing.dismiss(it) }
                emailDetails.pending?.let { emailDetails.dismiss(it) }
                try {
                    val target = DesktopSavePicker.emailExport(window)
                    if (target != null && lifetime.checkActive() && !vault.isLocked) emailExporter.open(target)
                } catch (_: Exception) { copyNotice = "The email export destination could not be selected." }
            }
        }, exportPreferences = {
            if (lifetime.checkActive() && preferencesExporter.status != DesktopPreferencesExport.Status.WRITING) {
                try {
                    val target = DesktopSavePicker.preferencesExport(window)
                    if (target != null && lifetime.checkActive() && !vault.isLocked) preferencesExporter.submit(target, lifetime::checkActive)
                } catch (_: Exception) { copyNotice = "The preferences destination could not be selected." }
            }
        }, importPreferences = {
            if (lifetime.checkActive() && preferencesImporter.pending == null) {
                layoutSettings.pending?.let { layoutSettings.dismiss(it) }
                autoLock.pending?.let { autoLock.dismiss(it) }
                try {
                    val source = DesktopSavePicker.openPreferences(window)
                    if (source != null && lifetime.checkActive() && !vault.isLocked) preferencesImporter.open(source, lifetime::checkActive)
                } catch (_: Exception) { copyNotice = "The preferences file could not be selected." }
            }
        }),
        appearance = presenter.appearance,
        artwork = artwork
    )
    hardwareRemoval.pending?.let { request ->
        EntryConfirmationDialog(title = "Remove local hardware unlock",
            message = when (request.status) {
                DesktopHardwareRemoval.Status.CONFIRM -> "Remove the hardware-unlock record from this data directory? Make sure you know the vault password before proceeding. This does not delete the vault, change its password, reset the security key, delete OATH accounts, or revoke copies of this record elsewhere. The current session stays open."
                DesktopHardwareRemoval.Status.REMOVING -> "Removing the local record… Closing cannot undo a deletion already started."
                DesktopHardwareRemoval.Status.REMOVED -> "Local hardware-unlock record is absent. Use the vault password next time; hardware unlock requires enrollment again."
                DesktopHardwareRemoval.Status.FAILED -> "Could not remove the local record. Check storage permissions; do not assume hardware unlock was disabled."
            }, confirmLabel = if (request.status == DesktopHardwareRemoval.Status.CONFIRM) "Remove local record" else "Close",
            dismissLabel = "Cancel", onConfirm = {
                if (request.status == DesktopHardwareRemoval.Status.CONFIRM) hardwareRemoval.confirm(request, lifetime::checkActive)
                else hardwareRemoval.dismiss(request)
            }, onDismiss = { hardwareRemoval.dismiss(request) })
    }
    hardwareCopy.pending?.let { request ->
        HardwareCopyDialog(inputKey = request, labels = request.labels, selected = request.selected,
            selecting = request.status == DesktopHardwareCopy.Status.SELECT, confirming = request.status == DesktopHardwareCopy.Status.CONFIRM,
            message = when (request.status) {
                DesktopHardwareCopy.Status.WRITING -> "Writing to security key… Touch it if prompted."
                DesktopHardwareCopy.Status.COPIED -> "Copied to key with touch required. Vault entry unchanged."
                DesktopHardwareCopy.Status.NO_KEY -> "No PC/SC security key found."
                DesktopHardwareCopy.Status.FAILED -> "Copy did not report success. Check the key before retrying: an issued device write may have completed."
                else -> null
            }, onSelect = { index -> hardwareCopy.select(request, index, lifetime::checkActive) },
            onSubmit = { pin -> hardwareCopy.submit(request, pin, lifetime::checkActive) },
            onDismiss = { hardwareCopy.dismiss(request) })
    }
    hardwareOath.pending?.let { request ->
        val rows = hardwareOath.rows(request, now)
        HardwareOathDialog(inputKey = request, labels = rows.map { it.label }, codes = rows.map { it.code }, counters = rows.map { it.counter },
            busy = request.busy, message = request.message,
            onSubmit = { pin, index -> hardwareOath.submit(request, pin, index, lifetime::checkActive) },
            onDismiss = { hardwareOath.dismiss(request) })
    }
    if (showStorageLocation) StorageLocationDialog(directory = location.dataDirectory,
        filenames = listOf(location.vaultFilename, location.backupFilename, location.settingsFilename, location.hardwareFilename),
        onDismiss = { showStorageLocation = false })
    val detailsRequest = emailDetails.pending
    if (detailsRequest != null) {
        val rows = emailDetails.rows(detailsRequest, presenter.isRevealed(detailsRequest.entryId))
        if (emailDetails.pending === detailsRequest) EmailDetailsDialog(rows = rows,
            secretsVisible = detailsRequest.secretsVisible,
            onEditSettings = {
                if (lifetime.checkActive() && emailDetails.rows(detailsRequest, presenter.isRevealed(detailsRequest.entryId)) != null) {
                    editing.open(detailsRequest.entryId, emailSettings = true)
                    emailDetails.dismiss(detailsRequest)
                }
                revision++
            },
            onEditPasswords = {
                if (lifetime.checkActive() && emailDetails.rows(detailsRequest, presenter.isRevealed(detailsRequest.entryId)) != null) {
                    editing.open(detailsRequest.entryId)
                    emailDetails.dismiss(detailsRequest)
                }
                revision++
            },
            onToggleSecrets = {
                if (lifetime.checkActive()) emailDetails.toggleSecrets(detailsRequest, presenter.isRevealed(detailsRequest.entryId))
                revision++
            }, onDismiss = { emailDetails.dismiss(detailsRequest); revision++ })
    }
    val openPgpRequest = openPgpImporter.pending
    if (openPgpRequest != null) {
        var keyFile by remember(openPgpRequest) { mutableStateOf<java.io.File?>(null) }
        OpenPgpImportDialog(inputKey = openPgpRequest, busy = openPgpRequest.busy,
            count = openPgpRequest.count, failed = openPgpRequest.failed, keySelected = keyFile != null,
            onChooseKey = {
                if (lifetime.checkActive() && openPgpImporter.pending === openPgpRequest) {
                    try {
                        val selected = DesktopSavePicker.openOpenPgpKey(window)
                        if (lifetime.checkActive() && openPgpImporter.pending === openPgpRequest && selected != null) keyFile = selected
                    } catch (_: Exception) { copyNotice = "The secret-key file could not be selected." }
                }
            }, onClearKey = { keyFile = null },
            onRead = { password -> openPgpImporter.submit(openPgpRequest, DesktopOpenPgpImport.Input(password, keyFile), lifetime::checkActive) },
            onConfirm = { replace ->
                if (openPgpImporter.confirmRestore(openPgpRequest, replace, lifetime::checkActive)) presenter.applyFilter(presenter.filter)
            }, onDismiss = { keyFile = null; openPgpImporter.dismiss(openPgpRequest) })
    }
    openPgpImporter.importedCount?.let { count -> EntryConfirmationDialog(title = "OpenPGP import",
        message = "Restored or added $count entries. Original backup and key files are unchanged.",
        confirmLabel = "OK", dismissLabel = "Close", onConfirm = { openPgpImporter.dismissNotice() }, onDismiss = { openPgpImporter.dismissNotice() }) }
    val linScpRequest = linScpImporter.pending
    if (linScpRequest != null) {
        var keyFile by remember(linScpRequest) { mutableStateOf<java.io.File?>(null) }
        LinScpImportDialog(inputKey = linScpRequest, busy = linScpRequest.busy,
            count = linScpRequest.count, failed = linScpRequest.failed, keySelected = keyFile != null,
            onChooseKey = {
                if (lifetime.checkActive() && linScpImporter.pending === linScpRequest) {
                    try {
                        val selected = DesktopSavePicker.openLinScpKey(window)
                        if (lifetime.checkActive() && linScpImporter.pending === linScpRequest && selected != null) keyFile = selected
                    } catch (_: Exception) { copyNotice = "The session key file could not be selected." }
                }
            }, onClearKey = { keyFile = null },
            onRead = { password -> linScpImporter.submit(linScpRequest, DesktopLinScpImport.Input(password, keyFile), lifetime::checkActive) },
            onConfirm = {
                if (linScpImporter.confirm(linScpRequest, lifetime::checkActive)) presenter.applyFilter(presenter.filter)
            }, onDismiss = { keyFile = null; linScpImporter.dismiss(linScpRequest) })
    }
    linScpImporter.importedCount?.let { count -> EntryConfirmationDialog(title = "LinSCP import",
        message = "Imported $count entries. Identical entries were skipped. Original files are unchanged.",
        confirmLabel = "OK", dismissLabel = "Close", onConfirm = { linScpImporter.dismissNotice() }, onDismiss = { linScpImporter.dismissNotice() }) }
    val emailRequest = emailImporter.pending
    if (emailRequest != null) EmailImportDialog(inputKey = emailRequest, busy = emailRequest.busy,
        count = emailRequest.count, failed = emailRequest.failed,
        onRead = { password -> emailImporter.submit(emailRequest, password, lifetime::checkActive) },
        onConfirm = {
            if (emailImporter.confirm(emailRequest, lifetime::checkActive)) presenter.applyFilter(presenter.filter)
        }, onDismiss = { emailImporter.dismiss(emailRequest) })
    emailImporter.importedCount?.let { count -> EntryConfirmationDialog(title = "FairEmail import",
        message = "Imported $count accounts. Identical accounts were skipped. The original file is unchanged.",
        confirmLabel = "OK", dismissLabel = "Close", onConfirm = { emailImporter.dismissNotice() }, onDismiss = { emailImporter.dismissNotice() }) }
    val passwordRequest = passwordImporter.pending
    if (passwordRequest != null) PasswordImportDialog(inputKey = passwordRequest, busy = passwordRequest.busy,
        count = passwordRequest.count, failed = passwordRequest.failed,
        onRead = { mode -> passwordImporter.submit(passwordRequest, mode, lifetime::checkActive) },
        onConfirm = {
            if (passwordImporter.confirm(passwordRequest, lifetime::checkActive)) presenter.applyFilter(presenter.filter)
        }, onDismiss = { passwordImporter.dismiss(passwordRequest) })
    passwordImporter.importedCount?.let { count -> EntryConfirmationDialog(title = "Password import",
        message = "Imported $count items. Identical entries were skipped. Protect or remove the original plaintext file separately.",
        confirmLabel = "OK", dismissLabel = "Close", onConfirm = { passwordImporter.dismissNotice() }, onDismiss = { passwordImporter.dismissNotice() }) }
    val importRequest = uriImporter.pending
    if (importRequest != null) UriImportDialog(inputKey = importRequest, message = importRequest.message,
        collecting = importRequest.collecting,
        onSubmit = { uri, allow ->
            if (uriImporter.submit(importRequest, uri, allow, lifetime::checkActive)) presenter.applyFilter(presenter.filter)
            revision++
        }, onDismiss = { imageReader.clear(); uriImporter.dismiss(importRequest); revision++ },
        imageBusy = imageReader.busy, imageReady = imageReader.ready, imageFailed = imageReader.failed,
        onChooseImage = {
            if (lifetime.checkActive() && uriImporter.pending === importRequest) {
                imageReader.clear()
                try {
                    val file = DesktopSavePicker.openQrImage(window)
                    if (file != null) imageReader.submit(file) {
                        lifetime.checkActive() && !vault.isLocked && uriImporter.pending === importRequest
                    }
                } catch (_: Exception) { copyNotice = "The QR image could not be selected." }
            }
        },
        onDiscardImage = { imageReader.clear() },
        onImportImage = { allow ->
            if (lifetime.checkActive() && uriImporter.pending === importRequest) {
                imageReader.take()?.let { uri ->
                    if (uriImporter.submit(importRequest, uri, allow, lifetime::checkActive)) presenter.applyFilter(presenter.filter)
                }
                revision++
            }
        })
    val restoreRequest = restorer.pending
    if (restoreRequest != null) EncryptedRestoreDialog(inputKey = restoreRequest,
        busy = restoreRequest.busy, count = restoreRequest.count, failed = restoreRequest.failed,
        onDecode = { password -> restorer.submit(restoreRequest, password, lifetime::checkActive) },
        onConfirm = { replace ->
            if (restorer.confirm(restoreRequest, replace, lifetime::checkActive)) {
                presenter.applyFilter(presenter.filter)
            }
        }, onDismiss = { restorer.dismiss(restoreRequest) })
    restorer.restoredCount?.let { count -> EntryConfirmationDialog(title = "Restore complete",
        message = "$count entries restored or added. Existing accounts are kept unchanged when merging.",
        confirmLabel = "OK", dismissLabel = "Close", onConfirm = { restorer.dismissNotice() }, onDismiss = { restorer.dismissNotice() }) }
    val preferencesRequest = preferencesImporter.pending
    if (preferencesRequest != null) PreferencesImportDialog(busy = preferencesRequest.busy,
        count = preferencesRequest.plan?.applied,
        skipped = preferencesRequest.plan?.let { it.unknownCount + it.refusedCount } ?: 0,
        failed = preferencesRequest.failed,
        onConfirm = {
            preferencesImporter.confirm(preferencesRequest, lifetime::checkActive) { plan ->
                if (plan.contains(PreferencesExport.UI_INACTIVITY_LOCK_SECONDS)) lifetime.changeTimeout(controller.savedInactivityLockSeconds())
                if (plan.contains(PreferencesExport.UI_CARD_LAYOUT)) presenter.setCardLayout(controller.savedCardLayout())
                if (plan.contains(PreferencesExport.UI_SORT_MODE)) presenter.applyFilter(presenter.filter.copy(sortMode = controller.savedSortMode()))
            }
        }, onDismiss = { preferencesImporter.dismiss(preferencesRequest) })
    preferencesImporter.appliedCount?.let { count -> EntryConfirmationDialog(title = "Preferences imported",
        message = "Applied $count settings. Vault entries and authentication material were not changed.",
        confirmLabel = "OK", dismissLabel = "Close", onConfirm = { preferencesImporter.dismissNotice() }, onDismiss = { preferencesImporter.dismissNotice() }) }
    archiveExporter.pending?.let { request ->
        if (request.status == DesktopArchiveExport.Status.SELECT) ArchiveExportDialog(
            labels = request.labels,
            onSelect = { index ->
                if (lifetime.checkActive() && archiveExporter.pending === request) {
                    try {
                        val target = DesktopSavePicker.archiveExport(window)
                        if (target != null) archiveExporter.choose(request, index, target, lifetime::checkActive)
                    } catch (_: Exception) { copyNotice = "The password export destination could not be selected." }
                }
            }, onDismiss = { archiveExporter.dismiss(request) })
        else EntryConfirmationDialog(title = "Export password archive (plaintext)",
            message = when (request.status) {
                DesktopArchiveExport.Status.CONFIRM -> "Export archive ${request.selected + 1}? This file restores the exact stored bytes and may contain passwords, notes, OTP seeds and other secrets. No encryption is added. Anyone with the file can read them. Store it securely and remove it when no longer needed. Existing files will not be overwritten."
                DesktopArchiveExport.Status.WRITING -> "Writing the archive. Closing cannot stop an already-started write."
                DesktopArchiveExport.Status.SAVED -> "Selected archive exported. Protect this plaintext file. Reopen Export for another archive."
                DesktopArchiveExport.Status.EXISTS -> "Destination already exists and was not overwritten. Reopen Export and choose a new filename."
                else -> "Export failed. A partial plaintext file may remain; inspect and remove it securely."
            }, confirmLabel = if (request.status == DesktopArchiveExport.Status.CONFIRM) "Export plaintext secrets" else "Close",
            dismissLabel = "Cancel", onConfirm = {
                if (request.status == DesktopArchiveExport.Status.CONFIRM) archiveExporter.confirm(request, lifetime::checkActive)
                else archiveExporter.dismiss(request)
            }, onDismiss = { archiveExporter.dismiss(request) })
    }
    passwordExporter.pending?.let { request ->
        if (request.status == DesktopPasswordExport.Status.SELECT) PasswordExportDialog(
            groups = request.groups.mapIndexed { index, group -> "Group ${index + 1}: ${group.dialect} — ${group.count} records" },
            foreign = request.foreignPasswords, archives = request.archives,
            onSelect = { index ->
                if (lifetime.checkActive() && passwordExporter.pending === request) {
                    try {
                        val target = DesktopSavePicker.passwordExport(window)
                        if (target != null) passwordExporter.choose(request, index, target, lifetime::checkActive)
                    } catch (_: Exception) { copyNotice = "The password export destination could not be selected." }
                }
            }, onDismiss = { passwordExporter.dismiss(request) })
        else EntryConfirmationDialog(title = "Export browser passwords (plaintext)",
            message = when (request.status) {
                DesktopPasswordExport.Status.CONFIRM -> "Export group ${request.selected + 1} (${request.groups[request.selected].count} records) from the snapshot? This unencrypted CSV contains passwords and may contain notes, OTP seeds and provider-specific secrets. Anyone with the file can read them. Store it securely and remove it when no longer needed. Existing files will not be overwritten."
                DesktopPasswordExport.Status.WRITING -> "Writing plaintext CSV. Closing cannot stop an already-started write."
                DesktopPasswordExport.Status.SAVED -> "Selected group exported. Protect this plaintext file. Reopen Export for another group."
                DesktopPasswordExport.Status.EXISTS -> "Destination already exists and was not overwritten. Reopen Export and choose a new filename."
                else -> "Export failed. A partial plaintext file may remain; inspect and remove it securely."
            }, confirmLabel = if (request.status == DesktopPasswordExport.Status.CONFIRM) "Export plaintext secrets" else "Close",
            dismissLabel = "Cancel", onConfirm = {
                if (request.status == DesktopPasswordExport.Status.CONFIRM) passwordExporter.confirm(request, lifetime::checkActive)
                else passwordExporter.dismiss(request)
            }, onDismiss = { passwordExporter.dismiss(request) })
    }
    emailExporter.pending?.let { request ->
        EntryConfirmationDialog(title = "Export FairEmail accounts (plaintext)",
            message = when (request.status) {
                DesktopEmailExport.Status.CONFIRM -> "Export ALL email accounts, including hidden or filtered-out accounts? This unencrypted JSON file contains incoming and outgoing passwords or tokens and every stored identity. Anyone with the file can read them. Store it securely and remove it when no longer needed. Existing files will not be overwritten."
                DesktopEmailExport.Status.WRITING -> "Writing plaintext accounts. Closing this dialog cannot stop a write that has already started."
                DesktopEmailExport.Status.SAVED -> "Exported ${request.count} email accounts. The file contains plaintext secrets; protect it."
                DesktopEmailExport.Status.EXISTS -> "The destination already exists and was not overwritten. Choose a new filename."
                DesktopEmailExport.Status.FAILED -> "Could not export email accounts (none available, invalid account or write failure). A partial plaintext file may remain; inspect and remove it securely."
            }, confirmLabel = if (request.status == DesktopEmailExport.Status.CONFIRM) "Export plaintext secrets" else "Close",
            dismissLabel = "Cancel", onConfirm = {
                if (request.status == DesktopEmailExport.Status.CONFIRM) emailExporter.confirm(request, lifetime::checkActive)
                else emailExporter.dismiss(request)
            }, onDismiss = { emailExporter.dismiss(request) })
    }
    preferencesExporter.status?.let { status -> EntryConfirmationDialog(title = "Export preferences",
        message = when (status) {
            DesktopPreferencesExport.Status.WRITING -> "Writing portable settings only—no vault entries, passwords or authentication material. Cancel stops queued work; a write already started may finish."
            DesktopPreferencesExport.Status.SAVED -> "Exported ${preferencesExporter.count} portable settings. No vault entries, passwords or authentication material were included."
            DesktopPreferencesExport.Status.EXISTS -> "That filename already exists. Choose a new filename; nothing was overwritten."
            DesktopPreferencesExport.Status.FAILED -> "Could not export preferences. A partial settings file may remain; choose a new filename before retrying."
        }, confirmLabel = if (status == DesktopPreferencesExport.Status.WRITING) "Cancel" else "OK",
        dismissLabel = "Close", onConfirm = { preferencesExporter.dismiss() }, onDismiss = { preferencesExporter.dismiss() }) }
    val pgpExportRequest = openPgpExporter.pending
    if (pgpExportRequest != null) {
        var recipientFile by remember(pgpExportRequest) { mutableStateOf<java.io.File?>(null) }
        OpenPgpExportDialog(busy = pgpExportRequest.busy, keySelected = recipientFile != null,
            error = when (pgpExportRequest.failure) {
                DesktopOpenPgpExport.Failure.KEY -> "Select a recipient public-key file."
                DesktopOpenPgpExport.Failure.EXISTS -> "That filename exists. Cancel and choose a new filename."
                DesktopOpenPgpExport.Failure.EXPORT -> "Could not export. Check the recipient key and destination. A partial encrypted file may remain; choose a new filename to retry."
                null -> null
            }, onChooseKey = {
                if (lifetime.checkActive() && openPgpExporter.pending === pgpExportRequest) {
                    try {
                        val key = DesktopSavePicker.openOpenPgpRecipient(window)
                        if (key != null && lifetime.checkActive() && openPgpExporter.pending === pgpExportRequest) recipientFile = key
                    } catch (_: Exception) { copyNotice = "The recipient key could not be selected." }
                }
            }, onExport = {
                if (lifetime.checkActive()) openPgpExporter.submit(pgpExportRequest, recipientFile)
            }, onDismiss = { recipientFile = null; openPgpExporter.dismiss(pgpExportRequest) })
    }
    if (openPgpExporter.saved) EntryConfirmationDialog(title = "OpenPGP export", message = "OpenPGP backup saved.",
        confirmLabel = "OK", dismissLabel = "Close", onConfirm = { openPgpExporter.dismissNotice() }, onDismiss = { openPgpExporter.dismissNotice() })
    val linScpExportRequest = linScpExporter.pending
    if (linScpExportRequest != null) EncryptedExportDialog(inputKey = linScpExportRequest, busy = linScpExportRequest.busy,
        title = "Export encrypted LinSCP vault",
        description = "Export all SSH keys and password credentials, including concealed entries, regardless of filters. OTPs, notes, email accounts and password archives are excluded. Protect the file with a new export password; existing files are never overwritten.",
        error = when (linScpExportRequest.failure) {
            DesktopEncryptedExport.Failure.PASSWORD -> "Enter matching, nonempty passwords."
            DesktopEncryptedExport.Failure.EXISTS -> "That filename exists. Cancel and choose a new filename."
            DesktopEncryptedExport.Failure.EXPORT -> "Could not export. Check that the vault has compatible entries and the destination is writable. A partial encrypted file may remain; choose a new filename to retry."
            null -> null
        }, onSubmit = { password, confirmation ->
            if (lifetime.checkActive()) linScpExporter.submit(linScpExportRequest, password, confirmation)
        }, onDismiss = { linScpExporter.dismiss(linScpExportRequest) })
    if (linScpExporter.saved) EntryConfirmationDialog(title = "LinSCP export", message = "Encrypted LinSCP vault saved.",
        confirmLabel = "OK", dismissLabel = "Close", onConfirm = { linScpExporter.dismissNotice() }, onDismiss = { linScpExporter.dismissNotice() })
    val exportRequest = exporter.pending
    if (exportRequest != null) EncryptedExportDialog(inputKey = exportRequest, busy = exportRequest.busy,
        error = when (exportRequest.failure) {
            DesktopEncryptedExport.Failure.PASSWORD -> "Enter matching, nonempty passwords."
            DesktopEncryptedExport.Failure.EXISTS -> "That filename already exists. Cancel and choose a new filename."
            DesktopEncryptedExport.Failure.EXPORT -> "Backup export failed. A partial encrypted file may remain; choose a new filename before retrying."
            null -> null
        },
        onSubmit = { password, confirmation ->
            if (lifetime.checkActive()) exporter.submit(exportRequest, password, confirmation)
        },
        onDismiss = { exporter.dismiss(exportRequest) })
    if (exporter.saved) EntryConfirmationDialog(title = "Encrypted backup", message = "Encrypted backup saved.",
        confirmLabel = "OK", dismissLabel = "Close", onConfirm = { exporter.dismissNotice() }, onDismiss = { exporter.dismissNotice() })
    val layoutRequest = layoutSettings.pending
    if (layoutRequest != null) CardLayoutDialog(inputKey = layoutRequest, current = layoutRequest.current,
        failed = layoutRequest.failed,
        onSave = { layout ->
            layoutSettings.submit(layoutRequest, layout, lifetime::checkActive, presenter::setCardLayout)
            revision++
        }, onDismiss = { layoutSettings.dismiss(layoutRequest); revision++ })
    val timeoutRequest = autoLock.pending
    if (timeoutRequest != null) AutoLockDialog(inputKey = timeoutRequest,
        currentSeconds = timeoutRequest.currentSeconds, options = DesktopAutoLockSettings.OPTIONS,
        failed = timeoutRequest.failed,
        onSave = { seconds -> autoLock.submit(timeoutRequest, seconds, lifetime); revision++ },
        onDismiss = { autoLock.dismiss(timeoutRequest); revision++ })
    if (moveFailed) EntryConfirmationDialog(title = "Move entry",
        message = "Could not move the entry. Its position or filter changed, or the vault could not be saved.",
        confirmLabel = "OK", dismissLabel = "Close", onConfirm = { moveFailed = false }, onDismiss = { moveFailed = false })
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
            onEmailSubmit = { input ->
                if (lifetime.checkActive() && creation.submitEmail(addRequest, input, lifetime::checkActive)) presenter.applyFilter(presenter.filter)
                revision++
            },
            error = when (addRequest.failure) {
                DesktopEntryCreation.Failure.INVALID_INPUT -> "Invalid entry. Check the fields and re-enter secret fields."
                DesktopEntryCreation.Failure.SAVE_FAILED -> "Could not add the entry (duplicate or save failure). Re-enter secret fields to retry."
                null -> null
            },
            onDismiss = { creation.dismiss(addRequest); revision++ },
            onSubmit = { input ->
                if (lifetime.checkActive() && creation.submit(addRequest, input, lifetime::checkActive)) presenter.applyFilter(presenter.filter)
                revision++
            }
        )
    }
    val editRequest = editing.pending
    if (editRequest != null && editRequest.isEmail && editRequest.emailSettings) {
        val initial = remember(editRequest) { editing.initialEmailSettings(editRequest) }
        if (initial != null) EmailSettingsDialog(inputKey = editRequest, initial = initial,
            failed = editRequest.failure != null,
            onDismiss = { editing.dismiss(editRequest); revision++ },
            onSubmit = { input ->
                if (lifetime.checkActive() && editing.submitEmailSettings(editRequest, input, presenter.isRevealed(editRequest.entryId), lifetime::checkActive)) {
                    presenter.entryRemoved(editRequest.entryId)
                }
                revision++
            })
    }
    if (editRequest != null && editRequest.isEmail && !editRequest.emailSettings) {
        val initial = remember(editRequest) { editing.initialEmailPasswords(editRequest) }
        if (initial != null) EmailPasswordDialog(inputKey = editRequest, initial = initial,
            failed = editRequest.failure != null,
            onDismiss = { editing.dismiss(editRequest); revision++ },
            onSubmit = { input ->
                if (lifetime.checkActive() && editing.submitEmailPasswords(editRequest, input, presenter.isRevealed(editRequest.entryId), lifetime::checkActive)) {
                    presenter.entryRemoved(editRequest.entryId)
                }
                revision++
            })
    }
    if (editRequest != null && editRequest.isPassword) {
        val initial = remember(editRequest) { editing.initialPasswordInput(editRequest) }
        if (initial != null) PasswordEntryDialog(inputKey = editRequest, initial = initial,
            failed = editRequest.failure != null,
            onDismiss = { editing.dismiss(editRequest); revision++ },
            onSubmit = { input ->
                if (lifetime.checkActive() && editing.submitPassword(editRequest, input, presenter.isRevealed(editRequest.entryId), lifetime::checkActive)) {
                    presenter.applyFilter(presenter.filter)
                }
                revision++
            })
    }
    if (editRequest != null && !editRequest.isPassword && !editRequest.isEmail) {
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
                if (editing.submit(editRequest, input, presenter.isRevealed(editRequest.entryId), lifetime::checkActive)) {
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
