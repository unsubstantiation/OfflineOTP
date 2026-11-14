package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ArchiveExportDialog(labels: List<String>, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Export password archive") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(text = "Choose one stored archive. Hidden and filtered-out archives are included. Export restores the exact imported bytes, without encryption, conversion or sanitization. Treat exported contents as untrusted data; do not execute spreadsheet formulas or scripts.")
            if (labels.isEmpty()) Text(text = "No password archives to export.")
            labels.forEachIndexed { index, label -> ForestTextAction(text = "Archive ${index + 1}: $label", onClick = { onSelect(index) }) }
        } }, confirmButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) })
}
