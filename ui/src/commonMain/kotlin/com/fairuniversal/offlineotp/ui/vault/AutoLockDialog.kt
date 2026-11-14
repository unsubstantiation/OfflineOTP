package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Host-supplied timeout options; opening/dismissing never changes a setting. */
@Composable
fun AutoLockDialog(inputKey: Any, currentSeconds: Int, options: List<Int>, failed: Boolean,
                   onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var selected by remember(inputKey) { mutableStateOf(currentSeconds) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Auto-lock timeout") },
        text = { Column {
            Text(text = "Lock after inactivity. Disabled keeps the vault open until you lock or close it.")
            for (seconds in (options + currentSeconds).distinct()) {
                val label = if (seconds == 0) "Disabled" else if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds seconds"
                ForestTextAction(text = if (seconds == selected) "Selected: $label" else label,
                    onClick = { selected = seconds })
            }
            if (failed) Text(text = "Could not save the setting. The previous timeout is still active.")
        } },
        confirmButton = { ForestTextAction(text = "Save", onClick = { onSave(selected) }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) })
}
