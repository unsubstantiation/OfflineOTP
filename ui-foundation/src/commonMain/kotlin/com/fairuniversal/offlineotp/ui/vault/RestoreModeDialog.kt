package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** One merge/replace disclosure for encrypted, OpenPGP and native-picker restore flows. */
@Composable
fun RestoreModeSelection(replace: Boolean, busy: Boolean, onReplaceChange: (Boolean) -> Unit) {
    Text(text = "Merge keeps current entries and adds entries not already present.")
    ForestTextAction(text = if (!replace) "Selected: Merge" else "Merge", enabled = !busy,
        onClick = { if (!busy) onReplaceChange(false) })
    ForestTextAction(text = if (replace) "Selected: Replace all current entries" else "Replace all current entries", enabled = !busy,
        onClick = { if (!busy) onReplaceChange(true) })
    if (replace) Text(text = "Warning: Restore will delete all current entries and replace them with this backup.")
}

/** Android asks before the picker; Compose hosts with decoded previews embed the selection above. */
@Composable
fun RestoreModeDialog(inputKey: Any, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var replace by remember(inputKey) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Restore backup") },
        text = { ScrollableDialogBody {
            Text(text = "Choose how to restore, then select a backup file. Nothing changes until the backup has been decrypted and validated.")
            RestoreModeSelection(replace = replace, busy = false, onReplaceChange = { replace = it })
        } },
        confirmButton = { ForestTextAction(text = if (replace) "Choose backup to replace entries" else "Choose backup to merge",
            onClick = { onConfirm(replace) }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) }
    )
}
