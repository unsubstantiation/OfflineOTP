// Compose Multiplatform dependency stub: androidx.compose.foundation.shape.
package androidx.compose.foundation.shape

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

data class RoundedCornerShape(val cornerRadius: CornerRadius) : Shape {
    constructor(percent: Int) : this(CornerRadius(percent.toFloat()))
    constructor(size: Dp) : this(CornerRadius(size.value))
    constructor(x: Dp, y: Dp) : this(CornerRadius(x.value, y.value))
}

val CircleShape: Shape = RoundedCornerShape(50)
