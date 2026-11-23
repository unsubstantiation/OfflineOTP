package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** Host-owned bundled artwork lookup. Null means the shared card should draw its letter tile. */
fun interface VaultArtwork {
    /** Receives only the persisted thumbnail name, never account labels, entries or secrets. */
    fun load(thumbnailName: String): ImageBitmap?
}

/** Shared card issuer artwork renderer with safe fallback to [VaultLetterTile]. */
@Composable
fun SharedIssuerArtwork(
    thumbnailName: String,
    title: String,
    sizeDp: Float,
    artwork: VaultArtwork? = null,
    seed: String = thumbnailName.ifBlank { title }
) {
    val image = remember(artwork, thumbnailName) {
        if (thumbnailName.isBlank()) null
        else try { artwork?.load(thumbnailName) } catch (_: Exception) { null }
    }
    if (image == null) {
        VaultLetterTile(seed = seed, title = title, sizeDp = sizeDp)
    } else {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.size(sizeDp.coerceAtLeast(0f).dp),
            contentScale = ContentScale.Fit
        )
    }
}

/** Shared 32dp catalog artwork preview icon for [SharedThumbnailPickerContent]. */
@Composable
fun SharedCatalogArtworkIcon(name: String, artwork: VaultArtwork? = null) {
    val image = remember(artwork, name) {
        if (name.isBlank()) null
        else try { artwork?.load(name) } catch (_: Exception) { null }
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
