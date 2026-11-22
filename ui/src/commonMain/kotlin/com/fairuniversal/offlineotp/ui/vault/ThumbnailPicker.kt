package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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
        iconContent = { name -> CatalogArtworkIcon(name = name, artwork = artwork) }
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
        iconContent = { name -> CatalogArtworkIcon(name = name, artwork = artwork) }
    )
}

@Composable
private fun CatalogArtworkIcon(name: String, artwork: VaultArtwork?) {
    val image = remember(artwork, name) {
        try { artwork?.load(name) } catch (_: Exception) { null }
    }
    if (image != null) {
        Box(modifier = Modifier.size(32.dp)) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                contentScale = ContentScale.Fit
            )
        }
    } else {
        VaultLetterTile(seed = name, title = name, sizeDp = 28f)
    }
}
