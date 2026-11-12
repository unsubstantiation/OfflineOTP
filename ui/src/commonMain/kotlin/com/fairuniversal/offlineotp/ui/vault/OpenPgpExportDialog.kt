package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun OpenPgpExportDialog(busy: Boolean, recipientNames: List<String>, error: String?,
                        onChooseKey: () -> Unit, onClearKeys: () -> Unit, onExport: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Export OpenPGP backup") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(text = "Export the full vault, including concealed entries and regardless of filters, encrypted to ALL selected recipient files. Verify every file belongs to an intended recipient. Anyone with ANY matching secret key can decrypt the entire backup; this is not shared-key or threshold protection.")
            Text(text = "Existing files will not be overwritten. A write already started may finish after cancel or lock; a failed write may leave a partial encrypted file.")
            Text(text = "Select 1–8 public-key files (16 MiB combined maximum). Filenames are not verified fingerprints.")
            recipientNames.forEachIndexed { index, name -> Text(text = "${index + 1}. $name") }
            ForestTextAction(text = "Add recipient public key", enabled = !busy && recipientNames.size < 8, onClick = onChooseKey)
            ForestTextAction(text = "Clear recipients", enabled = !busy && recipientNames.isNotEmpty(), onClick = onClearKeys)
            if (busy) Text(text = "Encrypting…")
            if (error != null) Text(text = error)
        } },
        confirmButton = { ForestTextAction(text = "Export full vault", enabled = !busy && recipientNames.isNotEmpty(), onClick = onExport) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) })
}
