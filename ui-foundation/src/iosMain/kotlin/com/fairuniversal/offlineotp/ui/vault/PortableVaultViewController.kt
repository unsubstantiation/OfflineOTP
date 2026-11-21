@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    private var cardLayout by mutableStateOf(PortableCardLayout.DEFAULT)
    private var surface by mutableStateOf<PortableSurfaceState?>(null)
    private var surfaceEpoch by mutableStateOf(0)

    /** Replaces the frame without replacing the composition. */
    fun updateVault(state: PortableVaultState) {
        vault = state
    }

    /** Updates the Compose vault card density/layout (`DEFAULT`, `COMPACT`, or `FULL`). */
    fun updateCardLayout(layout: String) {
        cardLayout = PortableCardLayout.values().firstOrNull { it.name == layout } ?: PortableCardLayout.DEFAULT
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
                    onDismissToast = { vaultActions.tool("dismissToast") },
                    cardLayout = cardLayout
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
        "delete", "thunderbirdConfirm" -> EntryConfirmationDialog(
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
        "sort" -> {
            val sortRows = rows.filter { it.key.startsWith("sort:") }
            EntryCopyDialog(
                fields = sortRows.map { it.title },
                title = dialog.title,
                cancelLabel = dialog.dismissLabel,
                onSelect = { index ->
                    sortRows.getOrNull(index)?.let { onAction(it.key) }
                },
                onDismiss = onDismiss
            )
        }
        "cardLayout" -> {
            val current = PortableCardLayout.values().firstOrNull { it.name == rowValue("layout") }
                ?: PortableCardLayout.DEFAULT
            SharedCardLayoutDialog(
                inputKey = dialogKey,
                current = current,
                failed = dialog.message.isNotEmpty(),
                onSave = { selected ->
                    surfaceActions.change("layout", selected.name)
                    confirm()
                },
                onDismiss = onDismiss,
                hideIssuer = rowValue("hideIssuer") == "true",
                showPreviousToken = rowValue("showPreviousToken") != "false",
                tokenGroupSize = rowValue("tokenGroupSize").toIntOrNull()?.takeIf { it == 0 || it == 2 || it == 3 } ?: 3,
                allowDefaultGrouping = false,
                onSaveDisplay = { selected, hideIssuer, showPreviousToken, tokenGroupSize ->
                    surfaceActions.change("layout", selected.name)
                    surfaceActions.change("hideIssuer", if (hideIssuer) "true" else "false")
                    surfaceActions.change("showPreviousToken", if (showPreviousToken) "true" else "false")
                    surfaceActions.change("tokenGroupSize", (tokenGroupSize ?: 3).toString())
                    confirm()
                },
                showIndividualCountdowns = rowValue("showIndividualTimeouts") != "false",
                onSaveCardDisplay = { selected, hideIssuer, showPreviousToken, tokenGroupSize, showIndividual ->
                    surfaceActions.change("layout", selected.name)
                    surfaceActions.change("hideIssuer", if (hideIssuer) "true" else "false")
                    surfaceActions.change("showPreviousToken", if (showPreviousToken) "true" else "false")
                    surfaceActions.change("tokenGroupSize", (tokenGroupSize ?: 3).toString())
                    surfaceActions.change("showIndividualTimeouts", if (showIndividual) "true" else "false")
                    confirm()
                }
            )
        }
        "passwordImportChoice" -> {
            val count = rowValue("previewCount").toIntOrNull()
            SharedPasswordImportDialog(
                inputKey = dialogKey,
                busy = dialog.busy,
                count = count,
                failed = dialog.message.isNotEmpty(),
                onRead = { mode ->
                    val rawMode = if (mode == PortablePasswordImportMode.ARCHIVE) "archive" else "individual"
                    surfaceActions.change("passwordImportMode", rawMode)
                    onAction("passwordImportPreview")
                },
                onConfirm = ::confirm,
                onDismiss = onDismiss
            )
        }
        "fairEmailExportSelect", "thunderbirdExportSelect", "searchIncludes" -> {
            val choiceRows = rows.filter { it.key.startsWith("choice:") }
            val initialSelected = choiceRows.indices.filter { choiceRows[it].checked }.toSet()
            MultiChoiceSelectionDialog(
                inputKey = dialogKey,
                title = dialog.title,
                message = dialog.sections.firstOrNull()?.title.orEmpty(),
                items = choiceRows.map { it.title },
                confirmLabel = dialog.confirmLabel,
                dismissLabel = dialog.dismissLabel,
                initialSelected = initialSelected,
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
        "qr", "qrFromEdit" -> {
            val matrixSize = rowValue("matrixSize").toIntOrNull() ?: 0
            val matrixBits = rowValue("matrixBits")
            if (matrixSize > 0 && matrixBits.length >= matrixSize * matrixSize) {
                QrCodeDialog(
                    title = dialog.title,
                    matrixSize = matrixSize,
                    matrixBits = matrixBits,
                    confirmLabel = dialog.confirmLabel,
                    onDismiss = onDismiss
                )
            } else {
                PortableSurfaceDialog(
                    state = dialog,
                    onChange = { key, value -> surfaceActions.change(key, value) },
                    onAction = { key -> onAction(key) },
                    onDismiss = onDismiss
                )
            }
        }
        "archiveExport" -> {
            val archiveRows = rows.filter { it.key.startsWith("archiveExportSelect:") }
            ArchiveExportDialog(
                labels = archiveRows.map { it.title },
                onSelect = { index ->
                    archiveRows.getOrNull(index)?.let { onAction(it.key) }
                },
                onDismiss = onDismiss
            )
        }
        "passwordExport" -> {
            val groupRows = rows.filter { it.key.startsWith("pwGroup:") }
            PasswordExportDialog(
                groups = groupRows.map { it.title },
                foreign = rowValue("foreign").toIntOrNull() ?: 0,
                archives = rowValue("archives").toIntOrNull() ?: 0,
                onSelect = { index ->
                    groupRows.getOrNull(index)?.let { onAction(it.key) }
                },
                onDismiss = onDismiss
            )
        }
        "thumbnailEdit" -> {
            val names = rowValue("catalog").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            SharedThumbnailPickerDialog(
                title = dialog.title,
                selected = rowValue("selected"),
                catalogNames = names,
                unchangedLabel = rowValue("unchangedLabel").ifEmpty { "Reset to Default" },
                onSelect = { chosen ->
                    surfaceActions.change("thumbnail", chosen)
                    confirm()
                },
                onDismiss = onDismiss
            )
        }
        "storageLocation" -> StorageLocationDialog(
            directory = rowValue("directory"),
            filenames = rows.filter { it.key.startsWith("file:") }.map { it.title },
            onDismiss = onDismiss
        )
        "emailEdit" -> {
            val accountFields = rows.filter { it.key.startsWith("acct:") }.map { row ->
                val parts = row.key.split(":", limit = 4)
                val kind = parts.getOrElse(2) { PortableEmailField.KIND_TEXT }
                val fieldName = parts.getOrElse(3) { row.title }
                PortableEmailField(name = fieldName, value = row.value, kind = kind)
            }
            val identityGroups = linkedMapOf<Int, MutableList<PortableEmailField>>()
            rows.filter { it.key.startsWith("id:") }.forEach { row ->
                val parts = row.key.split(":", limit = 5)
                val idIdx = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val kind = parts.getOrElse(3) { PortableEmailField.KIND_TEXT }
                val fieldName = parts.getOrElse(4) { row.title }
                identityGroups.getOrPut(idIdx) { mutableListOf() }
                    .add(PortableEmailField(name = fieldName, value = row.value, kind = kind))
            }
            val orderedIdentities = identityGroups.entries.sortedBy { it.key }.map { it.value.toList() }
            SharedEmailEditDialog(
                inputKey = dialogKey,
                initial = PortableEmailEditDraft(
                    issuer = rowValue("issuer"),
                    label = rowValue("label"),
                    hiddenLabel = rowValue("hiddenLabel"),
                    tags = rowValue("tags"),
                    thumbnail = rowValue("thumbnail"),
                    account = accountFields,
                    identities = orderedIdentities
                ),
                thumbnailNames = rowValue("thumbnailCatalog").split(",").filter { it.isNotEmpty() },
                error = dialog.message.ifEmpty { null },
                onSubmit = { draft ->
                    surfaceActions.change("issuer", draft.issuer)
                    surfaceActions.change("label", draft.label)
                    surfaceActions.change("hiddenLabel", draft.hiddenLabel)
                    surfaceActions.change("tags", draft.tags)
                    surfaceActions.change("thumbnail", draft.thumbnail)
                    draft.account.forEachIndexed { fIdx, field ->
                        surfaceActions.change("acctVal:$fIdx", field.value)
                    }
                    draft.identities.forEachIndexed { idIdx, fields ->
                        fields.forEachIndexed { fIdx, field ->
                            surfaceActions.change("idVal:$idIdx:$fIdx", field.value)
                        }
                    }
                    confirm()
                },
                onDismiss = onDismiss,
                onDelete = { onAction("delete") }
            )
        }
        "emailPasswordEdit" -> {
            val count = (rowValue("identityCount").toIntOrNull() ?: 1).coerceAtLeast(1)
            SharedEmailPasswordDialog(
                inputKey = dialogKey,
                initial = PortableEmailPasswordDraft(
                    incoming = "",
                    outgoing = List(count) { "" }
                ),
                failed = dialog.message.isNotEmpty(),
                errorText = dialog.message.ifEmpty { null },
                onSubmit = { draft ->
                    surfaceActions.change("inPass", draft.incoming)
                    draft.outgoing.forEachIndexed { idx, pw ->
                        surfaceActions.change("outPass:$idx", pw)
                    }
                    confirm()
                },
                onDismiss = onDismiss
            )
        }
        "emailSettingsEdit" -> {
            val count = (rowValue("identityCount").toIntOrNull() ?: 1).coerceAtLeast(1)
            val removable = rowValue("removableIndices")
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
            val outgoing = (0 until count).map { idx ->
                PortableEmailConnectionDraft(
                    host = rowValue("outHost:$idx"),
                    port = rowValue("outPort:$idx"),
                    encryption = rowValue("outEnc:$idx").toIntOrNull() ?: 0,
                    username = rowValue("outUser:$idx"),
                    authentication = rowValue("outAuth:$idx").ifEmpty { "0" }
                )
            }
            val identities = (0 until count).map { idx ->
                PortableEmailIdentityDraft(
                    name = rowValue("outName:$idx"),
                    address = rowValue("outAddr:$idx")
                )
            }
            SharedEmailSettingsDialog(
                inputKey = dialogKey,
                initial = PortableEmailSettingsDraft(
                    protocol = rowValue("protocol").toIntOrNull() ?: 0,
                    incoming = PortableEmailConnectionDraft(
                        host = rowValue("inHost"),
                        port = rowValue("inPort"),
                        encryption = rowValue("inEnc").toIntOrNull() ?: 0,
                        username = rowValue("inUser"),
                        authentication = rowValue("inAuth").ifEmpty { "0" }
                    ),
                    outgoing = outgoing,
                    identities = identities,
                    accountName = rowValue("accountName"),
                    removedIdentities = emptySet(),
                    removableIdentities = removable
                ),
                failed = dialog.message.isNotEmpty(),
                errorText = dialog.message.ifEmpty { null },
                onSubmit = { draft ->
                    draft.accountName?.let { surfaceActions.change("accountName", it) }
                    surfaceActions.change("protocol", draft.protocol.toString())
                    surfaceActions.change("inHost", draft.incoming.host)
                    surfaceActions.change("inPort", draft.incoming.port)
                    surfaceActions.change("inEnc", draft.incoming.encryption.toString())
                    surfaceActions.change("inUser", draft.incoming.username)
                    draft.incoming.authentication?.let { surfaceActions.change("inAuth", it) }
                    surfaceActions.change("removedIndices", draft.removedIdentities.sorted().joinToString(","))
                    draft.outgoing.forEachIndexed { idx, conn ->
                        surfaceActions.change("outHost:$idx", conn.host)
                        surfaceActions.change("outPort:$idx", conn.port)
                        surfaceActions.change("outEnc:$idx", conn.encryption.toString())
                        surfaceActions.change("outUser:$idx", conn.username)
                        conn.authentication?.let { surfaceActions.change("outAuth:$idx", it) }
                    }
                    draft.identities?.forEachIndexed { idx, sender ->
                        surfaceActions.change("outName:$idx", sender.name)
                        surfaceActions.change("outAddr:$idx", sender.address)
                    }
                    confirm()
                },
                onDismiss = onDismiss
            )
        }
        "emailEditor" -> SharedEmailAccountDialog(
            inputKey = dialogKey,
            initial = PortableEmailAccountDraft(
                name = rowValue("name"),
                address = rowValue("address"),
                protocol = rowValue("inProtoNum").toIntOrNull() ?: 0,
                incomingHost = rowValue("inHost"),
                incomingPort = rowValue("inPort").ifEmpty { "993" },
                incomingEncryption = rowValue("inEncNum").toIntOrNull() ?: 0,
                incomingAuth = rowValue("inAuth").ifEmpty { "1" },
                incomingUser = rowValue("inUser"),
                incomingPassword = "",
                outgoingHost = rowValue("outHost"),
                outgoingPort = rowValue("outPort").ifEmpty { "465" },
                outgoingEncryption = rowValue("outEncNum").toIntOrNull() ?: 0,
                outgoingAuth = rowValue("outAuth").ifEmpty { "1" },
                outgoingUser = rowValue("outUser"),
                outgoingPassword = "",
                tags = rowValue("tags")
            ),
            editing = dialog.title == "Edit email account",
            error = dialog.message.ifEmpty { null },
            onSubmit = { draft ->
                surfaceActions.change("name", draft.name)
                surfaceActions.change("address", draft.address)
                surfaceActions.change("inProto", draft.protocol.toString())
                surfaceActions.change("inHost", draft.incomingHost)
                surfaceActions.change("inPort", draft.incomingPort)
                surfaceActions.change("inEnc", draft.incomingEncryption.toString())
                surfaceActions.change("inAuth", draft.incomingAuth)
                surfaceActions.change("inUser", draft.incomingUser)
                surfaceActions.change("inPass", draft.incomingPassword)
                surfaceActions.change("outHost", draft.outgoingHost)
                surfaceActions.change("outPort", draft.outgoingPort)
                surfaceActions.change("outEnc", draft.outgoingEncryption.toString())
                surfaceActions.change("outAuth", draft.outgoingAuth)
                surfaceActions.change("outUser", draft.outgoingUser)
                surfaceActions.change("outPass", draft.outgoingPassword)
                surfaceActions.change("tags", draft.tags)
                confirm()
            },
            onDismiss = onDismiss
        )
        "editor" -> SharedManualEntryDialog(
            inputKey = dialogKey,
            initial = PortableEntryDraft(
                kind = rowValue("type").ifEmpty { "TOTP" },
                issuer = rowValue("issuer"),
                label = rowValue("label"),
                hiddenLabel = rowValue("hiddenLabel"),
                secret = rowValue("secret"),
                digits = rowValue("digits").ifEmpty { "6" },
                period = rowValue("period").ifEmpty { "30" },
                counter = rowValue("counter").ifEmpty { "0" },
                algorithm = rowValue("algorithm").ifEmpty { "SHA1" },
                pin = rowValue("pin"),
                publicKey = rowValue("publicKey"),
                privateKey = rowValue("privateKey"),
                sshHost = rowValue("sshHost"),
                sshPort = rowValue("sshPort"),
                sshUsername = rowValue("sshUsername"),
                sshPassword = rowValue("sshPassword"),
                tags = rowValue("tags"),
                thumbnail = rowValue("thumbnail")
            ),
            editing = dialog.title == "Edit entry",
            error = dialog.message.ifEmpty { null },
            onSwitchToEmail = if (dialog.title != "Edit entry") {
                { onAction("switchToEmailEditor") }
            } else {
                null
            },
            thumbnailNames = rowValue("thumbnailCatalog").split(",").filter { it.isNotEmpty() },
            onShowQrCode = if (dialog.title == "Edit entry" && rowValue("canShowQr") == "true") {
                { onAction("qrFromEdit") }
            } else {
                null
            },
            onDelete = if (dialog.title == "Edit entry") {
                { onAction("delete") }
            } else {
                null
            },
            onSubmit = { draft ->
                surfaceActions.change("type", draft.kind)
                surfaceActions.change("issuer", draft.issuer)
                surfaceActions.change("label", draft.label)
                surfaceActions.change("hiddenLabel", draft.hiddenLabel)
                surfaceActions.change("secret", draft.secret)
                surfaceActions.change("digits", draft.digits)
                surfaceActions.change("period", draft.period)
                surfaceActions.change("counter", draft.counter)
                surfaceActions.change("algorithm", draft.algorithm)
                surfaceActions.change("pin", draft.pin)
                surfaceActions.change("publicKey", draft.publicKey)
                surfaceActions.change("privateKey", draft.privateKey)
                surfaceActions.change("sshHost", draft.sshHost)
                surfaceActions.change("sshPort", draft.sshPort)
                surfaceActions.change("sshUsername", draft.sshUsername)
                surfaceActions.change("sshPassword", draft.sshPassword)
                surfaceActions.change("tags", draft.tags)
                surfaceActions.change("thumbnail", draft.thumbnail)
                surfaceActions.change("clearHiddenLabel", if (draft.clearHiddenLabel) "true" else "false")
                surfaceActions.change("clearPin", if (draft.clearPin) "true" else "false")
                surfaceActions.change("clearSshPassword", if (draft.clearSshPassword) "true" else "false")
                confirm()
            },
            onDismiss = onDismiss
        )
        else -> when {
            dialog.key.startsWith("passwordEdit:") && rows.isNotEmpty() -> {
                val extraRows = rows.filter { it.key.startsWith("pwCol:") }
                val extraFields = extraRows.map { row ->
                    val parts = row.key.split(":", limit = 4)
                    val kind = parts.getOrElse(2) { PortableEmailField.KIND_TEXT }
                    val colName = parts.getOrElse(3) { row.title }
                    PortableEmailField(name = colName, value = row.value, kind = kind)
                }
                SharedPasswordEntryDialog(
                    inputKey = dialogKey,
                    initial = PortablePasswordEditDraft(
                        url = rowValue("url"),
                        username = rowValue("username"),
                        password = "",
                        issuer = rowValue("issuer"),
                        label = rowValue("label"),
                        tags = rowValue("tags"),
                        thumbnail = rowValue("thumbnail"),
                        extraFields = extraFields,
                        customColumnsMode = rowValue("customColumnsMode") == "true",
                        archiveMode = rowValue("archiveMode") == "true",
                        archiveSizeBytes = rowValue("archiveSizeBytes").toIntOrNull() ?: 0
                    ),
                    failed = dialog.message.isNotEmpty(),
                    errorText = dialog.message.ifEmpty { null },
                    thumbnailNames = rowValue("thumbnailCatalog").split(",").filter { it.isNotEmpty() },
                    onDelete = { onAction("delete") },
                    onSubmit = { draft ->
                        surfaceActions.change("url", draft.url)
                        surfaceActions.change("username", draft.username)
                        surfaceActions.change("password", draft.password)
                        surfaceActions.change("issuer", draft.issuer)
                        surfaceActions.change("label", draft.label)
                        surfaceActions.change("tags", draft.tags)
                        surfaceActions.change("thumbnail", draft.thumbnail)
                        draft.extraFields.forEachIndexed { idx, field ->
                            surfaceActions.change("pwColVal:$idx", field.value)
                        }
                        confirm()
                    },
                    onDismiss = onDismiss
                )
            }
            dialog.key.startsWith("details:") && rows.any { it.key.startsWith("emailDetail:") } -> {
                val entryIdx = dialog.key.removePrefix("details:")
                val detailRows = buildList {
                    for (detail in rows.filter { it.key.startsWith("emailDetail:") }) {
                        add(PortableDetailRow(label = detail.title, value = detail.value))
                    }
                    val tags = rowValue("info:Tags")
                    if (tags.isNotEmpty()) {
                        add(PortableDetailRow(label = "Tags", value = tags))
                    }
                }
                val toggleRow = rows.firstOrNull { it.key.startsWith("toggleEmailSecrets:") }
                SharedEmailDetailsDialog(
                    rows = detailRows,
                    secretsVisible = toggleRow?.title == "Hide passwords",
                    onToggleSecrets = { onAction(toggleRow?.key ?: "toggleEmailSecrets:$entryIdx") },
                    onEditSettings = { onAction("editEmailSettings:$entryIdx") },
                    onEditPasswords = { onAction("editEmailPasswords:$entryIdx") },
                    onDismiss = onDismiss
                )
            }
            (dialog.key.startsWith("more:") || dialog.key.startsWith("copySelect:")) && rows.isNotEmpty() -> EntryCopyDialog(
                fields = rows.map { it.title },
                title = dialog.title,
                cancelLabel = dialog.dismissLabel,
                onSelect = { index ->
                    rows.getOrNull(index)?.let { onAction(it.key) }
                },
                onDismiss = onDismiss
            )
            dialog.key.startsWith("details:") && rows.isNotEmpty() -> {
                val infoLines = rows.filter { it.kind == PortableRow.KIND_INFO }.map { row ->
                    if (row.value.isNotEmpty()) "${row.title}: ${row.value}" else row.title
                }
                val actionRows = rows.filter { it.kind != PortableRow.KIND_INFO && it.enabled }
                EntryDetailsDialog(
                    title = dialog.title,
                    body = infoLines.joinToString("\n"),
                    confirmLabel = dialog.dismissLabel,
                    actions = actionRows.map { row ->
                        if (row.detail.isNotEmpty()) "${row.title} (${row.detail})" else row.title
                    },
                    onAction = { index ->
                        actionRows.getOrNull(index)?.let { onAction(it.key) }
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
}
