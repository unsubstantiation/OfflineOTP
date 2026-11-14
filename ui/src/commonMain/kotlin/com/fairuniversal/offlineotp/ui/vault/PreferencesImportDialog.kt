package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun PreferencesImportDialog(busy: Boolean, count: Int?, skipped: Int, failed: Boolean,
                            onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Import preferences") },
        text = { Column {
            Text(text = "Only desktop portable settings are imported. Vault entries, passwords and authentication material are not changed. Settings absent from the file keep their current values.")
            if (busy) Text(text = "Reading preferences…")
            if (count != null) {
                Text(text = "$count settings ready; $skipped unknown or disallowed settings skipped.")
                Text(text = "Apply updates the current sort, card layout and auto-lock timeout when included. A file may disable auto-lock; import only preferences you trust.")
            }
            if (failed) Text(text = "Could not import preferences. Check the file's desktop format, size and storage. Cancel to select another file.")
        } },
        confirmButton = { ForestTextAction(text = "Apply settings", enabled = !busy && count != null, onClick = onConfirm) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) })
}
