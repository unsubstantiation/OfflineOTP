package org.shadowice.flocke.andotp.Utilities

import java.text.NumberFormat
import java.util.Locale

/**
 * Pure-JVM token formatting, free of any Android dependency so it can live in the shared core.
 * The Android [Tools] facade keeps a delegating entry point for its existing callers.
 */
object TokenFormat {
    /** Formats the token with leading zeros (without grouping separators). */
    @JvmStatic
    fun formatTokenString(token: Int, digits: Int): String {
        val numberFormat = NumberFormat.getInstance(Locale.ENGLISH)
        numberFormat.minimumIntegerDigits = digits
        numberFormat.isGroupingUsed = false

        return numberFormat.format(token)
    }
}
