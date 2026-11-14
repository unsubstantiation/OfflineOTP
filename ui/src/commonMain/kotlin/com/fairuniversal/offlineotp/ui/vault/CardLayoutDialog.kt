package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fairuniversal.offlineotp.Utilities.Constants

@Composable
fun CardLayoutDialog(inputKey: Any, current: Constants.CardLayouts, failed: Boolean,
                     onSave: (Constants.CardLayouts) -> Unit, onDismiss: () -> Unit) {
    var selected by remember(inputKey) { mutableStateOf(current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Card layout") },
        text = { Column {
            Text(text = "Choose the Compose vault card layout. Saving keeps your current filter and revealed entries.")
            for (layout in Constants.CardLayouts.values()) {
                val label = when (layout) {
                    Constants.CardLayouts.DEFAULT -> "Default"
                    Constants.CardLayouts.COMPACT -> "Compact"
                    Constants.CardLayouts.FULL -> "Full"
                }
                ForestTextAction(text = if (selected == layout) "Selected: $label" else label,
                    onClick = { selected = layout })
            }
            if (failed) Text(text = "Could not save the layout. The current layout has not changed.")
        } },
        confirmButton = { ForestTextAction(text = "Save", onClick = { onSave(selected) }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) })
}
