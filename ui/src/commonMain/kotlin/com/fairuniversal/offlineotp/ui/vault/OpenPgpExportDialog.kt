package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun OpenPgpExportDialog(busy: Boolean, keySelected: Boolean, error: String?,
                        onChooseKey: () -> Unit, onExport: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Export OpenPGP backup") },
        text = { Column {
            Text(text = "Export the full vault, including concealed entries and regardless of filters, encrypted to the selected recipient. Verify the public-key file belongs to your intended recipient before exporting. Anyone with its matching secret key can decrypt this backup.")
            Text(text = "Existing files will not be overwritten. A write already started may finish after cancel or lock; a failed write may leave a partial encrypted file.")
            Text(text = if (keySelected) "Recipient public-key file selected." else "Select a recipient public-key file (maximum 16 MiB).")
            ForestTextAction(text = "Choose recipient public key", enabled = !busy, onClick = onChooseKey)
            if (busy) Text(text = "Encrypting…")
            if (error != null) Text(text = error)
        } },
        confirmButton = { ForestTextAction(text = "Export full vault", enabled = !busy && keySelected, onClick = onExport) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) })
}
