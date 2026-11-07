// Compose Multiplatform dependency stub: androidx.compose.ui.graphics.drawscope.
package androidx.compose.ui.graphics.drawscope

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

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
}
