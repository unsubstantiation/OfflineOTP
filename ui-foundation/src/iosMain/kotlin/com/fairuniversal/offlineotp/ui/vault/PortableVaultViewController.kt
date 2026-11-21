@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.portable.*
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import platform.UIKit.UIViewController

/** Native vault actions. Every value here is re-checked by the host against its live session. */
interface PortableVaultActions {
    fun selectTab(position: Int)
    fun search(text: String)
    fun toggleTag(tag: String)
    fun clearTags()
    fun toggleTagRule()
    fun tool(action: String)
    fun card(index: Int, action: String)
}

/**
 * Native dialog actions. [action] returns null on success, or a safe user-facing message when the
 * host refused the request; the shared form keeps the draft and shows that message.
 */
interface PortableSurfaceActions {
    fun change(key: String, value: String)
    fun action(key: String): String?
}

/**
 * The main-thread bridge for the whole unlocked iOS UI, including its dialogs. One Compose
 * composition owns the screen; native UIKit adapters own only file/camera pickers and Keychain I/O.
 */
class PortableVaultPresentation {
    private var active by mutableStateOf(true)
    private var vault by mutableStateOf(PortableVaultState.EMPTY)
    private var surface by mutableStateOf<PortableSurfaceState?>(null)
    private var surfaceEpoch by mutableIntStateOf(0)

    /** Replaces the frame without replacing the composition. */
    fun updateVault(state: PortableVaultState) {
        vault = state
    }

    /** Opens or replaces the visible dialog. A null state closes it. */
    fun updateSurface(state: PortableSurfaceState?) {
        val current = surface
        if (state?.key != current?.key) {
            surfaceEpoch++
        }
        // The host rebuilds its frame every second; a validation message from the last action must
        // survive that rebuild until the draft changes or the dialog closes.
        surface = if (state != null && current?.key == state.key && state.message.isEmpty()) {
            state.copy(message = current.message)
        } else {
            state
        }
    }

    fun close() {
        active = false
        surface = null
        surfaceEpoch++
    }

    private fun invokeSurfaceAction(surfaceActions: PortableSurfaceActions, key: String): String? {
        val failure = try {
            surfaceActions.action(key)
        } catch (_: Exception) {
            "The action could not be completed. Nothing was changed."
        }
        if (active && failure != null) {
            surface = surface?.copy(message = failure)
        }
        return failure
    }

    fun viewController(
        vaultActions: PortableVaultActions,
        surfaceActions: PortableSurfaceActions
    ): UIViewController = ComposeUIViewController {
        ForestTheme {
            if (active) {
                PortableVaultScreen(
                    state = vault,
                    onTab = { position -> vaultActions.selectTab(position) },
                    onQuery = { text -> vaultActions.search(text) },
                    onTag = { tag -> vaultActions.toggleTag(tag) },
                    onClearTags = { vaultActions.clearTags() },
                    onTool = { action -> vaultActions.tool(action) },
                    onCard = { index, action -> vaultActions.card(index, action) },
                    onDismissToast = { vaultActions.tool("dismissToast") }
                )
                val dialog = surface
                if (dialog != null) {
                    RenderSharedSurface(
                        dialog = dialog,
                        dialogKey = "${dialog.key}:$surfaceEpoch",
                        surfaceActions = surfaceActions,
                        onAction = { key -> invokeSurfaceAction(surfaceActions, key) },
                        onDismiss = { invokeSurfaceAction(surfaceActions, "dismiss") }
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderSharedSurface(
    dialog: PortableSurfaceState,
    dialogKey: String,
    surfaceActions: PortableSurfaceActions,
    onAction: (String) -> String?,
    onDismiss: () -> Unit
) {
    val rows = dialog.sections.flatMap { it.rows }
    fun row(key: String): PortableRow? = rows.firstOrNull { it.key == key }
    fun rowValue(key: String): String = row(key)?.value.orEmpty()
    fun rowDetail(key: String): String = row(key)?.detail.orEmpty()
    fun confirm() { onAction("confirm") }

    when (dialog.key) {
        "about" -> AboutDialog(
            version = rowValue("build").ifEmpty { rowValue("info") },
            onDismiss = onDismiss,
            onOpenLink = { url -> onAction("link:$url") == null }
        )
        "autoLock" -> AutoLockDialog(
            inputKey = dialogKey,
            currentSeconds = rowValue("seconds").toIntOrNull() ?: 0,
            options = AutoLockOptions.seconds,
            failed = dialog.message.isNotEmpty(),
            onSave = { seconds ->
                surfaceActions.change("seconds", seconds.toString())
                confirm()
            },
            onDismiss = onDismiss
        )
        "passwordChange" -> PasswordChangeDialog(
            inputKey = dialogKey,
            busy = dialog.busy,
            result = null,
            invalid = dialog.message.isNotEmpty(),
            maxFieldChars = PortableInputBounds.MAX_TEXT_CHARS,
            requiresUnlock = false,
            errorText = dialog.message.ifEmpty { null },
            onSubmit = { current, next, confirmation ->
                surfaceActions.change("current", current)
                surfaceActions.change("next", next)
                surfaceActions.change("confirmation", confirmation)
                confirm()
            },
            onDismiss = onDismiss
        )
        "biometricEnable" -> BiometricEnableDialog(
            title = dialog.title,
            detail = dialog.sections.firstOrNull()?.title.orEmpty().ifEmpty {
                "Enter the vault password to turn this on."
            },
            passwordLabel = row("password")?.title ?: "Vault password",
            confirmLabel = dialog.confirmLabel,
            cancelLabel = dialog.dismissLabel,
            initialError = dialog.message.ifEmpty { null },
            onConfirm = { password ->
                surfaceActions.change("password", password)
                onAction("confirm")
            },
            onDismiss = onDismiss
        )
        "prefsConfirm" -> PreferencesImportDialog(
            busy = dialog.busy,
            count = rowValue("count").toIntOrNull() ?: 1,
            skipped = rowValue("skipped").toIntOrNull() ?: 0,
            failed = dialog.message.isNotEmpty(),
            platformName = "iOS",
            failureText = dialog.message.ifEmpty { null },
            onConfirm = ::confirm,
            onDismiss = onDismiss
        )
        "delete" -> EntryConfirmationDialog(
            title = dialog.title,
            message = row("info")?.title ?: dialog.message,
            confirmLabel = dialog.confirmLabel,
            dismissLabel = dialog.dismissLabel,
            onConfirm = ::confirm,
            onDismiss = onDismiss
        )
        "pinEdit" -> SharedEntryPinDialog(
            entryId = rowValue("entryId").toLongOrNull() ?: 0L,
            title = dialog.title,
            inputLabel = row("pin")?.title ?: "PIN",
            confirmLabel = dialog.confirmLabel,
            dismissLabel = dialog.dismissLabel,
            maxLength = PortableInputBounds.MAX_TEXT_CHARS,
            initialValue = "",
            error = dialog.message.ifEmpty { null },
            inputKey = dialogKey,
            onSubmit = { pin ->
                surfaceActions.change("pin", pin)
                confirm()
            },
            onDismiss = onDismiss
        )
        "counterEdit" -> EntryCounterDialog(
            inputKey = dialogKey,
            initialCounter = rowValue("counter").ifEmpty { "0" },
            title = dialog.title,
            inputLabel = row("counter")?.title ?: "Counter",
            confirmLabel = dialog.confirmLabel,
            dismissLabel = dialog.dismissLabel,
            isValid = { candidate -> candidate.toLongOrNull()?.let { it >= 0L } == true },
            onSubmit = { text ->
                surfaceActions.change("counter", text)
                confirm()
            },
            onDismiss = onDismiss
        )
        "tagEdit" -> {
            val tagRows = rows.filter { it.key.startsWith("tag:") }
            val currentCsv = rowValue("tags")
            val currentSet = currentCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val states = LinkedHashMap<String, Boolean>()
            val counts = LinkedHashMap<String, Int>()
            for (tagRow in tagRows) {
                val tag = tagRow.title
                states[tag] = tagRow.checked || tag in currentSet
                tagRow.value.toIntOrNull()?.let { counts[tag] = it }
            }
            for (tag in currentSet) {
                if (tag !in states) states[tag] = true
            }
            TagsDialog(
                inputKey = dialogKey,
                initialStates = states,
                title = dialog.title,
                confirmLabel = dialog.confirmLabel,
                dismissLabel = dialog.dismissLabel,
                maxTagChars = PortableInputBounds.MAX_TAG_CHARS,
                tagCounts = counts,
                onTagCreated = { created ->
                    val next = (currentSet + created.trim()).filter { it.isNotEmpty() }
                    surfaceActions.change("tags", next.joinToString(", "))
                },
                onSelectionConfirmed = { selectedStates ->
                    val chosen = selectedStates.filterValues { it }.keys.joinToString(", ")
                    surfaceActions.change("tags", chosen)
                    confirm()
                },
                onDismiss = onDismiss
            )
        }
        "passwordImportChoice" -> {
            val actionRows = rows.filter { it.key.startsWith("passwordImportMode:") }
            EntryCopyDialog(
                fields = actionRows.map { it.title },
                title = dialog.title,
                cancelLabel = dialog.dismissLabel,
                message = dialog.sections.firstOrNull()?.title,
                onSelect = { index ->
                    actionRows.getOrNull(index)?.let { onAction(it.key) }
                },
                onDismiss = onDismiss
            )
        }
        "fairEmailExportSelect", "thunderbirdExportSelect" -> {
            val choiceRows = rows.filter { it.key.startsWith("choice:") }
            MultiChoiceSelectionDialog(
                inputKey = dialogKey,
                title = dialog.title,
                message = dialog.sections.firstOrNull()?.title.orEmpty(),
                items = choiceRows.map { it.title },
                confirmLabel = dialog.confirmLabel,
                dismissLabel = dialog.dismissLabel,
                onConfirm = { selected ->
                    val mapped = selected.sorted().mapNotNull { idx ->
                        choiceRows.getOrNull(idx)?.key?.removePrefix("choice:")
                    }.joinToString(",")
                    surfaceActions.change("selectedIndices", mapped)
                    confirm()
                },
                onDismiss = onDismiss
            )
        }
        "backupExportDialog" -> EncryptedExportDialog(
            inputKey = dialogKey,
            busy = dialog.busy,
            error = dialog.message.ifEmpty { null },
            onSubmit = { first, second ->
                surfaceActions.change("backupPassword", first)
                surfaceActions.change("backupConfirm", second)
                confirm()
            },
            onDismiss = onDismiss
        )
        "restore" -> {
            val count = rowValue("previewCount").toIntOrNull()
            EncryptedRestoreDialog(
                inputKey = dialogKey,
                busy = dialog.busy,
                count = count,
                failed = dialog.message.isNotEmpty(),
                onDecode = { password ->
                    surfaceActions.change("password", password)
                    onAction("restorePreview")
                },
                onConfirm = { replace ->
                    surfaceActions.change("replace", if (replace) "true" else "false")
                    confirm()
                },
                onDismiss = onDismiss
            )
        }
        "openpgpExportDialog" -> {
            val recipientNames = rows.filter { it.key.startsWith("recipient:") }.map { it.title }
            OpenPgpExportDialog(
                busy = dialog.busy,
                recipientNames = recipientNames,
                error = dialog.message.ifEmpty { null },
                onChooseKey = { onAction("openpgpRecipient") },
                onClearKeys = { onAction("openpgpClearRecipients") },
                onExport = { onAction("openpgpExport") },
                onDismiss = onDismiss
            )
        }
        "openpgpRestore" -> {
            val count = rowValue("previewCount").toIntOrNull()
            val keySelected = rowDetail("openpgpSecret") == "Key selected"
            OpenPgpImportDialog(
                inputKey = dialogKey,
                busy = dialog.busy,
                count = count,
                failed = dialog.message.isNotEmpty(),
                keySelected = keySelected,
                onChooseKey = { onAction("openpgpSecret") },
                onClearKey = { onAction("openpgpClearSecret") },
                onRead = { passphrase ->
                    surfaceActions.change("passphrase", passphrase)
                    onAction("openpgpPreview")
                },
                onConfirm = { replace ->
                    surfaceActions.change("replace", if (replace) "true" else "false")
                    confirm()
                },
                onDismiss = onDismiss
            )
        }
        "fairEmailImport" -> {
            val count = rowValue("previewCount").toIntOrNull()
            EmailImportDialog(
                inputKey = dialogKey,
                busy = dialog.busy,
                count = count,
                failed = dialog.message.isNotEmpty(),
                onRead = { password ->
                    surfaceActions.change("password", password)
                    onAction("fairEmailPreview")
                },
                onConfirm = ::confirm,
                onDismiss = onDismiss
            )
        }
        "linScpExport" -> {
            val choiceRows = rows.filter { it.key.startsWith("linScpExportSelect:") }
            LinScpExportDialog(
                inputKey = dialogKey,
                choices = choiceRows.map { it.title },
                busy = dialog.busy,
                error = dialog.message.ifEmpty { null },
                onSubmit = { selected, plainText, first, second ->
                    val entryIndices = selected.sorted().mapNotNull { idx ->
                        choiceRows.getOrNull(idx)?.key?.removePrefix("linScpExportSelect:")
                    }.joinToString(",")
                    surfaceActions.change("linScpSelectedIndices", entryIndices)
                    surfaceActions.change("linScpPlain", if (plainText) "true" else "false")
                    surfaceActions.change("linScpExportPassword", first)
                    surfaceActions.change("linScpExportConfirm", second)
                    confirm()
                },
                onDismiss = onDismiss
            )
        }
        "linScpImport" -> {
            val choiceRows = rows.filter { it.key.startsWith("linScpSelect:") }
            val count = choiceRows.size.takeIf { it > 0 }
            LinScpImportDialog(
                inputKey = dialogKey,
                busy = dialog.busy,
                count = count,
                failed = dialog.message.isNotEmpty(),
                keySelected = false,
                onChooseKey = {},
                onClearKey = {},
                onRead = { password ->
                    surfaceActions.change("linScpPassword", password)
                    onAction("linScpRead")
                },
                onConfirm = { selected ->
                    surfaceActions.change("linScpImportSelected", selected.sorted().joinToString(","))
                    confirm()
                },
                onDismiss = onDismiss,
                choices = choiceRows.map { it.title },
                secretLabel = "Backup password or base64 sessions.key",
                allowKeyFileSelection = false
            )
        }
        "uriImport" -> {
            val collecting = rowValue("collecting") == "true"
            val imageBusy = rowValue("imageBusy") == "true"
            val imageReady = rowValue("imageReady") == "true"
            val imageFailed = rowValue("imageFailed") == "true"
            UriImportDialog(
                inputKey = dialogKey,
                message = dialog.message.ifEmpty { rowValue("migrationNotice").ifEmpty { null } },
                collecting = collecting,
                imageBusy = imageBusy,
                imageReady = imageReady,
                imageFailed = imageFailed,
                onChooseImage = { onAction("qrImage") },
                onDiscardImage = { onAction("qrImageDiscard") },
                onImportImage = { allowAdditional ->
                    surfaceActions.change("allowAdditional", if (allowAdditional) "true" else "false")
                    onAction("qrImageImport")
                },
                onSubmit = { uri, allowAdditional ->
                    surfaceActions.change("uri", uri)
                    surfaceActions.change("allowAdditional", if (allowAdditional) "true" else "false")
                    confirm()
                },
                onDismiss = onDismiss
            )
        }
        else -> PortableSurfaceDialog(
            state = dialog,
            onChange = { key, value -> surfaceActions.change(key, value) },
            onAction = { key -> onAction(key) },
            onDismiss = onDismiss
        )
    }
}
