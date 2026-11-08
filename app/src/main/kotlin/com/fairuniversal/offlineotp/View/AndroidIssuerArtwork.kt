package com.fairuniversal.offlineotp.View

import android.content.Context
import android.graphics.Bitmap
import com.fairuniversal.offlineotp.Utilities.EntryThumbnail

/** Bounded, UI-thread-only cache of bundled marks; account identity never enters the cache. */
class AndroidIssuerArtwork(
    context: Context,
    private val capacity: Int = 64,
    private val read: (EntryThumbnail.EntryThumbnails) -> Bitmap? = { thumbnail ->
        EntryThumbnail.getThumbnailGraphic(context, "", "", 96, thumbnail)
    }
) {
    init { require(capacity > 0) }
    private val cache = LinkedHashMap<EntryThumbnail.EntryThumbnails, Bitmap?>()

    fun load(name: String): Bitmap? {
        val thumbnail = EntryThumbnail.EntryThumbnails.fromName(name)
        if (thumbnail == EntryThumbnail.EntryThumbnails.Default) return null
        if (cache.containsKey(thumbnail)) {
            val image = cache.remove(thumbnail)
            cache[thumbnail] = image
            return image
        }
        val image = try { read(thumbnail) } catch (_: Exception) { null }
        cache[thumbnail] = image
        if (cache.size > capacity) cache.remove(cache.keys.first())
        // Never recycle an evicted bitmap: a currently composed row may still reference it.
        return image
    }
}
