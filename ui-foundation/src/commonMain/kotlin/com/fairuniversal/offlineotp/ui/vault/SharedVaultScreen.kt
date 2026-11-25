package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/**
 * Shared unlocked-vault screen scaffold across Android, Desktop and iOS.
 *
 * Hosts supply their chrome (`headerContent`), empty-state (`emptyContent`), and drag-reorderable
 * card list (`listContent`), while sharing the container background, empty/list switching, and
 * optional ephemeral toast banner.
 */
@Composable
fun SharedVaultScaffold(
    isEmpty: Boolean,
    modifier: Modifier = Modifier,
    backgroundColor: Color = ForestPalette.backgroundDeep,
    centerEmpty: Boolean = true,
    toast: String = "",
    onDismissToast: (() -> Unit)? = null,
    headerContent: @Composable ColumnScope.() -> Unit,
    emptyContent: @Composable () -> Unit,
    listContent: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
            headerContent()
            if (isEmpty) {
                if (centerEmpty) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        emptyContent()
                    }
                } else {
                    emptyContent()
                }
            } else {
                listContent()
            }
        }
        if (toast.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = toast,
                    style = ForestType.body,
                    color = ForestPalette.textPrimary,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 32.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ForestPalette.surfaceElevated)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .let { base ->
                            if (onDismissToast != null) base.then(Modifier.clickable(onClick = onDismissToast)) else base
                        }
                )
            }
        }
    }
}

/** Shared viewport auto-scroll step used while dragging a vault entry near the top or bottom edge. */
fun sharedVaultViewportScroll(
    pointerY: Float,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    edgePx: Float = 56f,
    stepPx: Float = 12f
): Float = when {
    pointerY < viewportStartOffset + edgePx -> -stepPx
    pointerY > viewportEndOffset - edgePx -> stepPx
    else -> 0f
}

/** Shared lifted-item container used by reorderable vault lists across Android, Desktop and iOS. */
@Composable
fun SharedVaultDraggedItemBox(
    dragging: Boolean,
    pointerY: Float,
    grabOffset: Float,
    itemOffsetPx: () -> Float,
    activeShadowElevation: Float = 8f,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                if (dragging) {
                    translationY = pointerY - grabOffset - itemOffsetPx()
                    shadowElevation = activeShadowElevation
                }
            }
    ) {
        content()
    }
}

/** Common secondary-page navigation. Hosts retain Back/task/session authority. */
@Composable
fun SharedPageNavigation(onBack: (() -> Unit)?, enabled: Boolean = true,
                         content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        onBack?.let { ForestTextAction(text = "Back", onClick = it, enabled = enabled) }
        Box(Modifier.weight(1f)) { content() }
    }
}
