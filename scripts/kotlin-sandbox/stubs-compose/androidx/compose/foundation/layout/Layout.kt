// Compose Multiplatform dependency stub: androidx.compose.foundation.layout (containers, scopes,
// Arrangement and PaddingValues) plus the size/spacing modifiers.
package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

interface Arrangement {
    interface Horizontal
    interface Vertical
    interface HorizontalOrVertical : Horizontal, Vertical

    companion object {
        val Top: Vertical = object : Vertical {}
        val Center: HorizontalOrVertical = object : HorizontalOrVertical {}
        val Bottom: Vertical = object : Vertical {}
        val Start: Horizontal = object : Horizontal {}
        val End: Horizontal = object : Horizontal {}

        fun spacedBy(space: Dp): HorizontalOrVertical = object : HorizontalOrVertical {}
    }
}

private object NoModifier : Modifier

interface RowScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
}

interface ColumnScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
}

interface BoxScope {
    fun Modifier.align(alignment: Alignment): Modifier = this
}

@Composable
fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit
) {
}

@Composable
fun Column(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit
) {
}

@Composable
fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
}

/** The layout-only Box: a sized spacer with no children. */
@Composable
fun Box(modifier: Modifier) {
}

@Composable
fun Spacer(modifier: Modifier) {
}

interface PaddingValues

fun PaddingValues(all: Dp): PaddingValues = object : PaddingValues {}

fun PaddingValues(horizontal: Dp = Dp(0f), vertical: Dp = Dp(0f)): PaddingValues = object : PaddingValues {}

fun PaddingValues(start: Dp = Dp(0f), top: Dp = Dp(0f), end: Dp = Dp(0f), bottom: Dp = Dp(0f)): PaddingValues =
    object : PaddingValues {}

fun Modifier.padding(all: Dp): Modifier = this

fun Modifier.padding(horizontal: Dp = Dp(0f), vertical: Dp = Dp(0f)): Modifier = this

fun Modifier.padding(start: Dp = Dp(0f), top: Dp = Dp(0f), end: Dp = Dp(0f), bottom: Dp = Dp(0f)): Modifier = this

fun Modifier.size(size: Dp): Modifier = this

fun Modifier.size(width: Dp, height: Dp): Modifier = this

fun Modifier.width(width: Dp): Modifier = this

fun Modifier.height(height: Dp): Modifier = this

fun Modifier.widthIn(min: Dp = Dp(0f), max: Dp = Dp(Float.MAX_VALUE)): Modifier = this

fun Modifier.fillMaxWidth(fraction: Float = 1f): Modifier = this

fun Modifier.fillMaxHeight(fraction: Float = 1f): Modifier = this

fun Modifier.fillMaxSize(fraction: Float = 1f): Modifier = this
