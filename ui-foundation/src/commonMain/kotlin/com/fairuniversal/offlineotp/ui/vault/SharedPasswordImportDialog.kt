package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fairuniversal.offlineotp.ui.portable.PortablePasswordImportMode

/**
 * Shared browser/password-file import dialog across Android, Desktop and iOS.
 *
 * Host owns file bytes and preview/install lifecycle; Compose holds only the selected import mode.
 */
@Composable
fun SharedPasswordImportDialog(
    inputKey: Any,
    busy: Boolean,
    count: Int?,
    failed: Boolean,
    onRead: (PortablePasswordImportMode) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember(inputKey) { mutableStateOf(PortablePasswordImportMode.INDIVIDUAL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Import password file") },
        text = { ScrollableDialogBody {
            Text(text = "Browser export files contain plaintext passwords. Importing does not delete or protect the source file. Maximum file size: 4 MiB.")
            if (count == null && !busy) {
                ForestTextAction(
                    text = if (mode == PortablePasswordImportMode.INDIVIDUAL) "Selected: Individual credentials" else "Individual credentials",
                    onClick = { mode = PortablePasswordImportMode.INDIVIDUAL }
                )
                ForestTextAction(
                    text = if (mode == PortablePasswordImportMode.ARCHIVE) "Selected: Keep original file" else "Keep original file",
                    onClick = { mode = PortablePasswordImportMode.ARCHIVE }
                )
                Text(text = "Keep original file stores its bytes as one encrypted vault entry, not editable individual passwords.")
            }
            if (busy) Text(text = "Reading password file…")
            if (count != null) Text(text = "$count items ready. Import merges them into the current vault; identical entries are skipped. No current entries will be replaced.")
            if (failed) SharedStatusMessage("Could not import the file. Check the format, file size, vault capacity and storage, then retry.")
        } },
        confirmButton = {
            ForestTextAction(
                text = if (count == null) "Read file" else "Import",
                enabled = !busy,
                onClick = { if (count == null) onRead(mode) else onConfirm() }
            )
        },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) }
    )
}
