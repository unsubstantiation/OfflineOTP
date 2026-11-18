package com.fairuniversal.offlineotp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The shared Dark Forest look: one colour scheme and one type scale for Android and desktop.
 *
 * Material 3's [darkColorScheme] is fed the app's own tokens rather than a generated theme, so a
 * card, a field and a divider keep the exact colours the two existing clients use. Type is kept as
 * named [TextStyle] values instead of a Material `Typography`: the clients need a handful of
 * specific styles (a monospaced amber code, a small label, a body line), and naming them means a
 * component asks for `ForestType.code` rather than re-deriving a size at every call site.
 */
object ForestType {
    /** Screen and app-bar titles. */
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)

    /** The account name under the issuer. */
    val subtitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)

    /** Ordinary prose: empty states, hints, dialog copy. */
    val body = TextStyle(fontSize = 14.sp)

    /** One-time codes: monospaced so the digits do not shift as they change. */
    val code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp
    )

    /** The previous step's code, shown smaller under the current one. */
    val previousCode = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        letterSpacing = 1.sp
    )

    /** Small caps-ish labels: tabs, chips, counters, seconds remaining. */
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)

    /** Tab strip entries. */
    val tab = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)

    /** Fixed-width metadata such as an HOTP counter or an mOTP PIN prompt. */
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
}

private val ForestColorScheme = darkColorScheme(
    primary = ForestPalette.primary,
    onPrimary = ForestPalette.onPrimary,
    secondary = ForestPalette.accent,
    onSecondary = ForestPalette.onPrimary,
    background = ForestPalette.background,
    onBackground = ForestPalette.textPrimary,
    surface = ForestPalette.surface,
    onSurface = ForestPalette.textPrimary,
    surfaceVariant = ForestPalette.surfaceVariant,
    onSurfaceVariant = ForestPalette.textSecondary,
    outline = ForestPalette.divider,
    error = ForestPalette.warning
)

/** Wraps [content] in the shared theme. Call once per window or screen root. */
@Composable
fun ForestTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ForestColorScheme, content = content)
}
