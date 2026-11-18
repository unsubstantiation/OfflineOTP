package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/** A clickable label, the only button style the shared UI uses. */
@Composable
fun ForestTextAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    color: Color = ForestPalette.accent
) {
    Text(
        text = text,
        style = ForestType.label,
        color = if (enabled) color else ForestPalette.textHint,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** Shared bounded-dialog body: long warnings and fields remain reachable on small windows. */
@Composable
fun ScrollableDialogBody(content: @Composable () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) { content() }
}
