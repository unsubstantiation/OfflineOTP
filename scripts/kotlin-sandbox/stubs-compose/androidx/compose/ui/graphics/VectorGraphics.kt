// Compose Multiplatform dependency stub: androidx.compose.ui.graphics (paths and brushes).
package androidx.compose.ui.graphics

import androidx.compose.ui.geometry.Offset

class PathFillType private constructor(val value: Int) {
    companion object {
        val NonZero: PathFillType = PathFillType(0)
        val EvenOdd: PathFillType = PathFillType(1)
    }
}

class ClipOp private constructor(val value: Int) {
    companion object {
        val Difference: ClipOp = ClipOp(0)
        val Intersect: ClipOp = ClipOp(1)
    }
}

interface Path {
    var fillType: PathFillType
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun quadraticTo(x1: Float, y1: Float, x2: Float, y2: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)
    fun close()
    fun addPath(path: Path, offset: Offset = Offset.Zero)
}

private class StubPath : Path {
    override var fillType: PathFillType = PathFillType.NonZero
    override fun moveTo(x: Float, y: Float) {}
    override fun lineTo(x: Float, y: Float) {}
    override fun quadraticTo(x1: Float, y1: Float, x2: Float, y2: Float) {}
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {}
    override fun close() {}
    override fun addPath(path: Path, offset: Offset) {}
}

fun Path(): Path = StubPath()

abstract class Brush {
    companion object {
        fun linearGradient(
            vararg colorStops: Pair<Float, Color>,
            start: Offset = Offset.Zero,
            end: Offset = Offset.Zero
        ): Brush = SolidColor(Color.Transparent)

        fun radialGradient(
            vararg colorStops: Pair<Float, Color>,
            center: Offset = Offset.Zero,
            radius: Float = 1f
        ): Brush = SolidColor(Color.Transparent)
    }
}

class SolidColor(val value: Color) : Brush()
