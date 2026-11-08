// Compose Multiplatform dependency stub: androidx.compose.ui geometry.
package androidx.compose.ui.geometry

data class Offset(val x: Float, val y: Float) {
    companion object {
        val Zero: Offset = Offset(0f, 0f)
    }
}

data class Size(val width: Float, val height: Float) {
    val minDimension: Float get() = if (width < height) width else height
    val maxDimension: Float get() = if (width > height) width else height

    companion object {
        val Zero: Size = Size(0f, 0f)
    }
}

data class CornerRadius(val x: Float, val y: Float = x) {
    constructor(radius: Float) : this(radius, radius)

    companion object {
        val Zero: CornerRadius = CornerRadius(0f)
    }
}
