package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.BrowserPasswordCsv
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog
import com.fairuniversal.offlineotp.ui.portable.PortablePasswordEditDraft

/** Thin `:ui` adapter mapping [PasswordEntryInput] onto [SharedPasswordEntryDialog] in `:ui-foundation`. */
@Composable
fun PasswordEntryDialog(
    inputKey: Any,
    initial: PasswordEntryInput,
    failed: Boolean,
    onSubmit: (PasswordEntryInput) -> Unit,
    onDismiss: () -> Unit,
    editing: Boolean = true,
    errorText: String? = null,
    submitNotice: String? = null,
    artwork: VaultArtwork? = null,
    onSwitchToManual: (() -> Unit)? = null,
    onSwitchToEmail: (() -> Unit)? = null,
    onSwitchToUriImport: (() -> Unit)? = null,
    onManageTags: (() -> Unit)? = null,
    onViewDetails: (() -> Unit)? = null,
    onExportArchive: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val unchangedLabel = if (editing) "Keep current artwork" else "Automatic from issuer"
    SharedPasswordEntryDialog(
        inputKey = inputKey,
        initial = PortablePasswordEditDraft(
            url = initial.url,
            username = initial.username,
            password = initial.password,
            issuer = initial.issuer,
            label = initial.label,
            tags = initial.tags.orEmpty(),
            thumbnail = initial.thumbnail.orEmpty(),
            extraFields = initial.extraFields.orEmpty(),
            customColumnsMode = initial.customColumnsMode,
            archiveMode = initial.archiveMode,
            archiveSizeBytes = initial.archiveSizeBytes,
            note = initial.note
        ),
        failed = failed,
        editing = editing,
        submitNotice = submitNotice,
        maxFieldChars = BrowserPasswordCsv.MAX_FIELD_CHARS,
        errorText = errorText,
        thumbnailNames = ThumbnailCatalog.NAMES,
        artwork = artwork,
        artworkPicker = { selected, onSelect, onCancel ->
            ThumbnailPicker(
                selected = selected.ifEmpty { null },
                artwork = artwork,
                unchangedLabel = unchangedLabel,
                onSelect = { chosen ->
                    onSelect(if (editing) chosen ?: initial.thumbnail.orEmpty() else chosen.orEmpty())
                },
                onCancel = onCancel
            )
        },
        onSwitchToManual = if (!editing) onSwitchToManual else null,
        onSwitchToEmail = if (!editing) onSwitchToEmail else null,
        onSwitchToUriImport = if (!editing) onSwitchToUriImport else null,
        onManageTags = if (editing) onManageTags else null,
        onViewDetails = if (editing) onViewDetails else null,
        onExportArchive = if (editing) onExportArchive else null,
        onDelete = if (editing) onDelete else null,
        onSubmit = { draft ->
            onSubmit(
                PasswordEntryInput(
                    url = draft.url,
                    username = draft.username,
                    password = draft.password,
                    issuer = draft.issuer,
                    label = draft.label,
                    tags = draft.tags,
                    thumbnail = draft.thumbnail.ifEmpty { null },
                    extraFields = draft.extraFields,
                    customColumnsMode = draft.customColumnsMode,
                    archiveMode = draft.archiveMode,
                    archiveSizeBytes = draft.archiveSizeBytes,
                    note = draft.note
                )
            )
        },
        onDismiss = onDismiss
    )
}
