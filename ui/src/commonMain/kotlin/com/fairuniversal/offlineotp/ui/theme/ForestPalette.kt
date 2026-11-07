package com.fairuniversal.offlineotp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Dark Forest colour tokens, one list for every client.
 *
 * These are the values already declared twice — once in `app/src/main/res/values/colors.xml` for
 * the Android Views and once in the desktop `ForestTheme` for Swing. With a shared Compose UI they
 * become a single Kotlin object; the Android resource names are kept as comments so a future
 * cleanup can retire the XML copies without guessing which token is which.
 */
object ForestPalette {
    /** `forest_background` */
    val background = Color(0xFF101A14)

    /** `forest_background_deep` — behind cards and dialogs. */
    val backgroundDeep = Color(0xFF0A110D)

    /** `forest_surface` */
    val surface = Color(0xFF1A2C21)

    /** `forest_surface_elevated` */
    val surfaceElevated = Color(0xFF21372A)

    /** `forest_surface_variant` — selection and pressed states. */
    val surfaceVariant = Color(0xFF263F30)

    /** `forest_appbar` */
    val appBar = Color(0xFF1B4D2E)

    /** `colorPrimary` */
    val primary = Color(0xFF4CAF50)

    /** `colorAccent` */
    val accent = Color(0xFF81C784)

    /** `forest_text_primary` */
    val textPrimary = Color(0xFFE6F0E8)

    /** `forest_text_secondary` */
    val textSecondary = Color(0xFFA3BCA9)

    /** `forest_text_hint` */
    val textHint = Color(0xFF92AC97)

    /** `forest_on_primary` */
    val onPrimary = Color(0xFF0D1711)

    /** `forest_divider` */
    val divider = Color(0xFF2B4434)

    /** `warning_red` — also the colour a code turns as its step runs out. */
    val warning = Color(0xFFFF8A80)

    /** `forest_otp_amber` — one-time codes, ≥ 6.3:1 on every forest surface. */
    val otpAmber = Color(0xFFFFC857)

    /** `forest_otp_amber_muted` — countdown track and secondary amber accents. */
    val otpAmberMuted = Color(0xFF7A5C22)

    /** `forest_thumbnail_background` */
    val thumbnailBackground = Color(0xFFC8D8CC)

    /** `forest_scrim` */
    val scrim = Color(0xAA0A110D)

    /** Selection background for a highlighted row. */
    val selection = Color(0x3381C784)

    /**
     * The artwork tint offered to issuers whose mark is not bundled. Deterministic per name, so the
     * same account keeps the same tile on the phone and on the desktop.
     */
    fun letterTileTint(seed: String): Color {
        val palette = listOf(
            Color(0xFF2E7D32), Color(0xFF00695C), Color(0xFF37474F), Color(0xFF4E342E),
            Color(0xFF455A64), Color(0xFF5D4037), Color(0xFF1B5E20), Color(0xFF01579B)
        )
        if (seed.isEmpty()) return palette.first()
        var hash = 0
        for (character in seed) hash = hash * 31 + character.code
        return palette[((hash % palette.size) + palette.size) % palette.size]
    }
}
