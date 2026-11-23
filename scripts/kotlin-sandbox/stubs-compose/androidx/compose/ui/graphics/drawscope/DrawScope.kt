// Compose Multiplatform dependency stub: androidx.compose.ui.graphics.drawscope.
package androidx.compose.ui.graphics.drawscope

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

sealed interface DrawStyle

class Fill : DrawStyle {
    companion object {
        val Default: Fill = Fill()
    }
}

class Stroke(
    val width: Float = 0f,
    val miter: Float = 4.0f,
    val cap: androidx.compose.ui.graphics.StrokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
    val join: androidx.compose.ui.graphics.StrokeJoin = androidx.compose.ui.graphics.StrokeJoin.Miter
) : DrawStyle

interface DrawScope {
    val size: Size

    fun drawArc(
        color: Color,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        alpha: Float = 1f,
        style: DrawStyle = Fill.Default
    )

    fun drawCircle(
        color: Color,
        radius: Float = size.minDimension / 2,
        center: Offset = Offset(size.width / 2, size.height / 2),
        alpha: Float = 1f,
        style: DrawStyle = Fill.Default
    )

    fun drawLine(
        color: Color,
        start: Offset,
        end: Offset,
        strokeWidth: Float = 0f,
        cap: androidx.compose.ui.graphics.StrokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
        alpha: Float = 1f
    )

    fun drawRect(
        color: Color,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        alpha: Float = 1f,
        style: DrawStyle = Fill.Default
    )

    fun drawRoundRect(
        color: Color,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        cornerRadius: CornerRadius = CornerRadius.Zero,
        style: DrawStyle = Fill.Default,
        alpha: Float = 1f
    )

    fun drawPath(
        path: Path,
        color: Color,
        alpha: Float = 1f,
        style: DrawStyle = Fill.Default
    )

    fun drawPath(
        path: Path,
        brush: Brush,
        alpha: Float = 1f,
        style: DrawStyle = Fill.Default
    )
}

interface DrawTransform {
    val size: Size
    val center: Offset
        get() = Offset(size.width / 2f, size.height / 2f)

    fun translate(left: Float = 0f, top: Float = 0f)
    fun rotate(degrees: Float, pivot: Offset = center)
    fun scale(scaleX: Float, scaleY: Float = scaleX, pivot: Offset = center)
    fun clipPath(path: Path, clipOp: ClipOp = ClipOp.Intersect)
}

inline fun DrawScope.withTransform(
    transformBlock: DrawTransform.() -> Unit,
    drawBlock: DrawScope.() -> Unit
) {
    val noop = object : DrawTransform {
        override val size: Size get() = this@withTransform.size
        override fun translate(left: Float, top: Float) {}
        override fun rotate(degrees: Float, pivot: Offset) {}
        override fun scale(scaleX: Float, scaleY: Float, pivot: Offset) {}
        override fun clipPath(path: Path, clipOp: ClipOp) {}
    }
    noop.transformBlock()
    drawBlock()
}
