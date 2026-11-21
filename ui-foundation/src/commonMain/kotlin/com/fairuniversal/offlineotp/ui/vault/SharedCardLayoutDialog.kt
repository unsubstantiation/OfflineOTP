package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.PortableCardLayout

/** Shared Compose vault card layout selector across Desktop and iOS. */
@Composable
fun SharedCardLayoutDialog(
    inputKey: Any,
    current: PortableCardLayout,
    failed: Boolean,
    onSave: (PortableCardLayout) -> Unit,
    onDismiss: () -> Unit,
    hideIssuer: Boolean? = null,
    onSaveAppearance: ((PortableCardLayout, Boolean) -> Unit)? = null
) {
    var selected by remember(inputKey) { mutableStateOf(current) }
    var hideIssuerChecked by remember(inputKey) { mutableStateOf(hideIssuer == true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Card layout") },
        text = {
            Column {
                Text(text = "Choose the Compose vault card layout. Saving keeps your current filter and revealed entries.")
                for (layout in PortableCardLayout.values()) {
                    val label = when (layout) {
                        PortableCardLayout.DEFAULT -> "Default"
                        PortableCardLayout.COMPACT -> "Compact"
                        PortableCardLayout.FULL -> "Full"
                    }
                    ForestTextAction(
                        text = if (selected == layout) "Selected: $label" else label,
                        onClick = { selected = layout }
                    )
                }
                if (hideIssuer != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { hideIssuerChecked = !hideIssuerChecked }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = hideIssuerChecked,
                            onCheckedChange = { hideIssuerChecked = it }
                        )
                        Text(text = "Hide issuer label on cards")
                    }
                }
                if (failed) Text(text = "Could not save the layout. The current layout has not changed.")
            }
        },
        confirmButton = {
            ForestTextAction(text = "Save", onClick = {
                if (onSaveAppearance != null && hideIssuer != null) {
                    onSaveAppearance(selected, hideIssuerChecked)
                } else {
                    onSave(selected)
                }
            })
        },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) }
    )
}
