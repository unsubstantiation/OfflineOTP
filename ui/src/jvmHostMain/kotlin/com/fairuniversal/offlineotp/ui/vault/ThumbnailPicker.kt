package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog

/** Thin `:ui` adapter mapping [ThumbnailCatalog.NAMES] and [VaultArtwork] onto [SharedThumbnailPickerContent]. */
@Composable
internal fun ThumbnailPicker(
    selected: String?,
    artwork: VaultArtwork?,
    unchangedLabel: String,
    onSelect: (String?) -> Unit,
    onCancel: () -> Unit
) {
    SharedThumbnailPickerContent(
        selected = selected.orEmpty(),
        catalogNames = ThumbnailCatalog.NAMES,
        unchangedLabel = unchangedLabel,
        onSelect = { chosen -> onSelect(chosen.ifEmpty { null }) },
        onCancel = onCancel,
        artwork = artwork
    )
}

/** Thin `:ui` adapter mapping [ThumbnailCatalog.NAMES] and [VaultArtwork] onto [SharedThumbnailPickerDialog]. */
@Composable
fun ThumbnailPickerDialog(
    title: String,
    selected: String?,
    artwork: VaultArtwork?,
    unchangedLabel: String,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    SharedThumbnailPickerDialog(
        title = title,
        selected = selected.orEmpty(),
        catalogNames = ThumbnailCatalog.NAMES,
        unchangedLabel = unchangedLabel,
        dismissLabel = "Cancel",
        onSelect = { chosen -> onSelect(chosen.ifEmpty { null }) },
        onDismiss = onDismiss,
        artwork = artwork
    )
}
