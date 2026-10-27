package org.shadowice.flocke.andotp.Utilities

/** Shared limits for all entry/import paths, including legacy JSON. */
object InputPolicy {
    const val MAX_URI_CHARS = 16384
    const val MAX_ENTRIES = 2000
    const val MAX_JSON_DEPTH = 32
    const val MAX_TEXT_CHARS = 1024
    const val MAX_TAGS = 32
    const val MAX_TAG_CHARS = 128
    const val MAX_SECRET_BYTES = 2048
    const val MAX_PERIOD_SECONDS = 86400
    const val MAX_IMAGE_DIMENSION = 8192
    const val MAX_IMAGE_PIXELS = 4_000_000L


    @JvmStatic
    fun text(value: String?, limit: Int) {
        if (value != null && value.length > limit) {
            throw IllegalArgumentException("Entry text exceeds limit")
        }
    }

    /** Stop deeply nested untrusted JSON before the platform's recursive parser. */
    @JvmStatic
    fun jsonDepth(data: String) {
        var depth = 0
        var quoted = false
        var i = 0
        while (i < data.length) {
            val c = data[i]
            if (quoted && c == '\\') { i += 2; continue }
            if (c == '"') { quoted = !quoted; i++; continue }
            if (quoted) { i++; continue }
            // Android JSON also accepts single quotes/comments; disallow these extensions
            // so they cannot disagree with this scanner about string boundaries.
            if (c == '\'' || c == '/' || c == '#' || c == '\\') throw IllegalArgumentException("Invalid JSON syntax")
            if (c == '[' || c == '{') {
                if (++depth > MAX_JSON_DEPTH) throw IllegalArgumentException("JSON nesting exceeds limit")
            } else if (c == ']' || c == '}') {
                if (--depth < 0) throw IllegalArgumentException("Invalid JSON nesting")
            }
            i++
        }
        // Full JSON syntax/type validation is still performed by the platform parser.
        if (depth != 0 || quoted) throw IllegalArgumentException("Invalid JSON nesting")
    }

    @JvmStatic
    fun validImage(width: Int, height: Int): Boolean {
        return width > 0 && height > 0 && width <= MAX_IMAGE_DIMENSION
                && height <= MAX_IMAGE_DIMENSION && width.toLong() * height <= MAX_IMAGE_PIXELS
    }

    @JvmStatic
    fun tokenParameters(period: Int, digits: Int, counter: Long) {
        // Keep legacy custom 1–9 digit OTPs, but never overflow the int modulus.
        if (period < 1 || period > MAX_PERIOD_SECONDS || digits < 1 || digits > 9
                || counter < 0 || counter == Long.MAX_VALUE) {
            throw IllegalArgumentException("Invalid OTP parameters")
        }
    }
}
