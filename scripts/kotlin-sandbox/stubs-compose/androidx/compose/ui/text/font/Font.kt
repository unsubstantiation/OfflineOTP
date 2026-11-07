// Compose Multiplatform dependency stub: androidx.compose.ui.text.font.
package androidx.compose.ui.text.font

class FontFamily {
    companion object {
        val Monospace: FontFamily = FontFamily()
        val Default: FontFamily = FontFamily()
    }
}

class FontWeight private constructor(val weight: Int) {
    companion object {
        val Normal: FontWeight = FontWeight(400)
        val Medium: FontWeight = FontWeight(500)
        val SemiBold: FontWeight = FontWeight(600)
        val Bold: FontWeight = FontWeight(700)
    }
}
