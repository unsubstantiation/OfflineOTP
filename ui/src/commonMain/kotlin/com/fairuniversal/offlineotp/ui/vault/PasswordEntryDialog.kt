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
    artwork: VaultArtwork? = null,
    onManageTags: (() -> Unit)? = null,
    onViewDetails: (() -> Unit)? = null,
    onExportArchive: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
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
            archiveSizeBytes = initial.archiveSizeBytes
        ),
        failed = failed,
        maxFieldChars = BrowserPasswordCsv.MAX_FIELD_CHARS,
        thumbnailNames = ThumbnailCatalog.NAMES,
        artworkPicker = { selected, onSelect, onCancel ->
            ThumbnailPicker(
                selected = selected.ifEmpty { null },
                artwork = artwork,
                unchangedLabel = "Keep current artwork",
                onSelect = { chosen -> onSelect(chosen ?: initial.thumbnail.orEmpty()) },
                onCancel = onCancel
            )
        },
        onManageTags = onManageTags,
        onViewDetails = onViewDetails,
        onExportArchive = onExportArchive,
        onDelete = onDelete,
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
                    archiveSizeBytes = draft.archiveSizeBytes
                )
            )
        },
        onDismiss = onDismiss
    )
}
