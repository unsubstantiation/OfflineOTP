// Compose Multiplatform dependency stub: androidx.compose.ui units.
package androidx.compose.ui.unit

@JvmInline
value class Dp(val value: Float)

@JvmInline
value class TextUnit(val value: Float) {
    companion object {
        val Unspecified: TextUnit = TextUnit(Float.NaN)
    }
}

val Int.dp: Dp get() = Dp(this.toFloat())
val Float.dp: Dp get() = Dp(this)
val Double.dp: Dp get() = Dp(this.toFloat())

val Int.sp: TextUnit get() = TextUnit(this.toFloat())
val Float.sp: TextUnit get() = TextUnit(this)
val Double.sp: TextUnit get() = TextUnit(this.toFloat())
