package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fairuniversal.offlineotp.Utilities.PasswordImport

@Composable
fun PasswordImportDialog(inputKey: Any, busy: Boolean, count: Int?, failed: Boolean,
                         onRead: (PasswordImport.Mode) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var mode by remember(inputKey) { mutableStateOf(PasswordImport.Mode.INDIVIDUAL) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Import password file") },
        text = { Column {
            Text(text = "Browser export files contain plaintext passwords. Importing does not delete or protect the source file. Maximum file size: 4 MiB.")
            if (count == null && !busy) {
                ForestTextAction(text = if (mode == PasswordImport.Mode.INDIVIDUAL) "Selected: Individual credentials" else "Individual credentials",
                    onClick = { mode = PasswordImport.Mode.INDIVIDUAL })
                ForestTextAction(text = if (mode == PasswordImport.Mode.ARCHIVE) "Selected: Keep original file" else "Keep original file",
                    onClick = { mode = PasswordImport.Mode.ARCHIVE })
                Text(text = "Keep original file stores its bytes as one encrypted vault entry, not editable individual passwords.")
            }
            if (busy) Text(text = "Reading password file…")
            if (count != null) Text(text = "$count items ready. Import merges them into the current vault; identical entries are skipped. No current entries will be replaced.")
            if (failed) Text(text = "Could not import the file. Check the format, file size, vault capacity and storage, then retry.")
        } },
        confirmButton = { ForestTextAction(text = if (count == null) "Read file" else "Import", enabled = !busy,
            onClick = { if (count == null) onRead(mode) else onConfirm() }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) })
}
