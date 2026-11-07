// Compose Multiplatform dependency stub: androidx.compose.ui.graphics (colour).
package androidx.compose.ui.graphics

@JvmInline
value class Color(val value: Long) {
    companion object {
        val Transparent: Color = Color(0x00000000)
        val Unspecified: Color = Color(0f, 0f, 0f)
    }
}

fun Color(red: Float, green: Float, blue: Float, alpha: Float = 1f): Color = Color(0L)

fun Color(red: Int, green: Int, blue: Int, alpha: Int = 255): Color = Color(0L)
