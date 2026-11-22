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
import androidx.compose.ui.unit.dp
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
