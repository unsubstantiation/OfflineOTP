package com.fairuniversal.offlineotp.desktop.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
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
import com.fairuniversal.offlineotp.ui.vault.VaultActions
import com.fairuniversal.offlineotp.ui.vault.VaultAppearance
import com.fairuniversal.offlineotp.ui.vault.VaultCardState
import com.fairuniversal.offlineotp.ui.vault.VaultFilter
import com.fairuniversal.offlineotp.ui.vault.VaultFilterCombination
import com.fairuniversal.offlineotp.ui.vault.VaultProjection
import com.fairuniversal.offlineotp.ui.vault.VaultScreen
import com.fairuniversal.offlineotp.ui.vault.VaultStrings
import com.fairuniversal.offlineotp.ui.vault.rememberNowMillis

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Compose Desktop entry point: the shared `:ui` screen over the existing portable vault.
 *
 * This is the migration-stage host, not a replacement for the Swing client yet. It deliberately
 * contains no presentation logic of its own: the frame comes from [VaultProjection], the widgets
 * come from `:ui`, and unlocking/saving go through the same [VaultController] and
 * [DesktopVaultState] the Swing window uses, so both desktop clients read and write one vault.
 *
 * What this host does not have yet (tracked in docs/compose-unified-ui-plan.md): add/edit/delete
 * dialogs, backup/restore, hardware keys, preferences and the idle auto-lock. The shared screen
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
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "OfflineOTP",
        state = rememberWindowState(width = 520.dp, height = 820.dp)
    ) {
        ForestTheme {
            val vault = session
            if (vault == null) {
                UnlockScreen(
                    strings = VaultStrings.English,
                    error = failure,
                    busy = busy,
                    hint = if (controller.isInitialized()) {
                        null
                    } else {
                        "There is no vault in this folder yet. Choose a password to create one."
                    },
                    onSubmit = { password ->
                        busy = true
                        val outcome = if (controller.isInitialized()) {
                            controller.openVault(password)
                        } else {
                            controller.createVault(password)
                        }
                        busy = false
                        when (outcome) {
                            is VaultController.Session.Loaded -> {
                                failure = null
                                session = DesktopVaultState(outcome.key).apply { load(outcome.entries) }
                            }
                            is VaultController.Session.Error -> failure = failureText(outcome.failure)
                        }
                    }
                )
            } else {
                VaultContent(
                    controller = controller,
                    vault = vault,
                    onLock = {
                        vault.lock()
                        session = null
                        failure = null
                    }
                )
            }
        }
    }
}

@Composable
private fun VaultContent(controller: VaultController, vault: DesktopVaultState, onLock: () -> Unit) {
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
    val projection = remember(vault) {
        VaultProjection(vault.entryList, vault.presentation).applyFilter(initialFilter)
    }
    var filter by remember(vault) { mutableStateOf(initialFilter) }
    var revealed by remember(vault) { mutableStateOf(emptySet<Long>()) }
    val anchor = remember(vault) { System.currentTimeMillis() }
    val now = rememberNowMillis(anchor)
    val state = remember(now, filter, revealed, vault) { projection.snapshot(filter, now, revealed) }

    fun updateFilter(next: VaultFilter) {
        filter = next
        // The presentation is mutated here, in the event, never during composition.
        projection.applyFilter(next)
    }

    VaultScreen(
        state = state,
        strings = strings,
        actions = VaultActions(
            onCopyToken = { card -> card.token?.let { copyToken(it) } },
            onToggleReveal = { card ->
                revealed =
                    if (card.id in revealed) revealed - card.id else revealed + card.id
            },
            onNextCounter = { card -> advanceCounter(controller, vault, card) },
            // Every transition is the shared value rule in :ui, not a copy of it: the Android host
            // will call the same helpers for its search box, tab strip and tag drawer.
            onQueryChange = { query -> updateFilter(filter.withQuery(query)) },
            onSelectCategory = { category -> updateFilter(filter.withCategory(category)) },
            onToggleTag = { tag -> updateFilter(filter.withTagToggled(tag)) },
            onToggleUntagged = { updateFilter(filter.withUntaggedToggled()) },
            onClearFilters = { updateFilter(filter.clearedFilters()) },
            onSelectSort = { mode ->
                updateFilter(filter.withSortMode(mode))
                controller.saveSortMode(mode)
            },
            onLock = {
                revealed = emptySet()
                onLock()
            }
        ),
        appearance = VaultAppearance()
    )
}

/**
 * Advances one HOTP counter and persists the vault. The save goes through the same
 * [VaultController.save] path the Swing client uses; a failed write leaves the in-memory counter
 * advanced, exactly like a failed save there, and the next frame shows the recalculated code.
 */
private fun advanceCounter(controller: VaultController, vault: DesktopVaultState, card: VaultCardState) {
    val entry = vault.snapshot().firstOrNull { it.getListId() == card.id } ?: return
    if (!entry.isCounterBased()) return
    runCatching {
        entry.setCounter(entry.getCounter() + 1L)
        entry.updateOTP(true)
        controller.save(vault.key, vault.snapshot())
    }
}

/** Copies the digits only: spaces are a rendering detail, not part of the code. */
private fun copyToken(grouped: String) {
    val token = grouped.replace(" ", "")
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(token), null)
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
