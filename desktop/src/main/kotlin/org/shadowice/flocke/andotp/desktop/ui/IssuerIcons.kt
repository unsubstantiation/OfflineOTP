package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Utilities.ThumbnailAssets

import java.awt.image.BufferedImage
import java.io.InputStream

/**
 * Resolves an entry's persisted thumbnail name to the brand artwork the Android app bundles.
 *
 * The desktop build copies the Android vector drawables onto its classpath under `/thumbnails/`
 * (see desktop/build.gradle) and [ThumbnailAssets] maps a stored name such as `GitHub` to its file
 * `thumb_github`. A name with no bundled mark — including `Default` and unknown issuers — returns
 * null so the caller can draw its generated letter tile instead.
 *
 * Rasterized marks are cached: the table repaints every second, and parsing XML there would show
 * up as input lag. Only the event dispatch thread renders cards, but the cache is synchronized so
 * tests and future background warm-up cannot corrupt it.
 */
internal object IssuerIcons {
    /** Pixels per cached mark; cards request smaller sizes and downscale smoothly. */
    const val SIZE = 96

    private const val RESOURCES = "/thumbnails/"

    private val cache = HashMap<String, BufferedImage?>()

    /** Cached artwork for [thumbnailName], or null when the name has no bundled mark. */
    fun artwork(thumbnailName: String?): BufferedImage? = synchronized(cache) {
        val file = ThumbnailAssets.drawableFile(thumbnailName) ?: return@synchronized null
        if (cache.containsKey(file)) return@synchronized cache[file]
        val image = load(file) { path -> IssuerIcons::class.java.getResourceAsStream(path) }
        cache[file] = image
        image
    }

    /** Uncached load through [loader], for tests that supply their own artwork. */
    internal fun artwork(thumbnailName: String?, loader: (String) -> InputStream?): BufferedImage? {
        val file = ThumbnailAssets.drawableFile(thumbnailName) ?: return null
        return load(file, loader)
    }

    /** Classpath path of a bundled mark, or null when the name has no artwork. */
    fun resourcePath(thumbnailName: String?): String? =
        ThumbnailAssets.drawableFile(thumbnailName)?.let { "$RESOURCES$it.xml" }

    private fun load(file: String, loader: (String) -> InputStream?): BufferedImage? =
        loader("$RESOURCES$file.xml")?.use { VectorArtwork.render(it, SIZE) }
}
