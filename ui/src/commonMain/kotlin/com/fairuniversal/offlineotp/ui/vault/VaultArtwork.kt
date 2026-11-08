package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.ui.graphics.ImageBitmap

/** Host-owned bundled artwork lookup. Null means the shared card should draw its letter tile. */
fun interface VaultArtwork {
    /** Receives only the persisted thumbnail name, never account labels, entries or secrets. */
    fun load(thumbnailName: String): ImageBitmap?
}
