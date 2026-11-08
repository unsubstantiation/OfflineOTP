package com.fairuniversal.offlineotp.Utilities

import java.text.NumberFormat
import java.util.Locale

/**
 * Pure-JVM token formatting, free of any Android dependency so it can live in the shared core.
 * The Android [Tools] facade keeps a delegating entry point for its existing callers.
 */
object TokenFormat {
    /** Android-compatible grouping from the right, including alphabetic codes and short prefixes. */
    @JvmStatic
    fun groupFromRight(code: String?, chunkSize: Int): String? {
        if (chunkSize <= 0 || code == null) return code
        val grouped = StringBuilder()
        var index = code.length
        while (index > 0) {
            grouped.insert(0, code.substring(maxOf(index - chunkSize, 0), index))
            grouped.insert(0, " ")
            index -= chunkSize
        }
        return grouped.toString().trim()
    }

    /** Formats the token with leading zeros (without grouping separators). */
    @JvmStatic
    fun formatTokenString(token: Int, digits: Int): String {
        val numberFormat = NumberFormat.getInstance(Locale.ENGLISH)
        numberFormat.minimumIntegerDigits = digits
        numberFormat.isGroupingUsed = false

        return numberFormat.format(token)
    }
}
