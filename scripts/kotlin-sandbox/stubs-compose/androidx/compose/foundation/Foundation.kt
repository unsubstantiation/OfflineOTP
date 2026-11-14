// Compose Multiplatform dependency stub: androidx.compose.foundation drawing, input and scrolling.
package androidx.compose.foundation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp

fun Modifier.background(color: Color, shape: Shape = RectangleShape): Modifier = this

fun Modifier.border(width: Dp, color: Color, shape: Shape = RectangleShape): Modifier = this

fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit
): Modifier = this

class ScrollState(val value: Int = 0)

@Composable
fun rememberScrollState(initial: Int = 0): ScrollState = ScrollState(initial)

fun Modifier.horizontalScroll(state: ScrollState, enabled: Boolean = true): Modifier = this

@Composable
fun Canvas(modifier: Modifier, onDraw: DrawScope.() -> Unit) {
}

/** Unused by the shared UI; declared so a future call site has the real coordinate shape. */
internal val Origin: Offset = Offset.Zero

internal val NoPadding: PaddingValues? = null

fun Modifier.combinedClickable(
    enabled: Boolean = true,
    onDoubleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = this

@RequiresOptIn
annotation class ExperimentalFoundationApi

@ExperimentalFoundationApi
fun Modifier.basicMarquee(iterations: Int = 3): Modifier = this

fun Modifier.verticalScroll(state: ScrollState): Modifier = this
