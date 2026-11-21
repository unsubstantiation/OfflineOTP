package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Only group counts/dialects, never columns or credential values, enter the chooser. */
@Composable
fun PasswordExportDialog(groups: List<String>, foreign: Int, archives: Int,
                         onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Export browser passwords") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(text = "Choose one snapshot group. Groups keep their original CSV columns to avoid losing data. Hidden and filtered-out records are included. Reopen Export to save another group. CSV files contain plaintext secrets; treat cells as untrusted data, not spreadsheet formulas.")
            Text(text = "Excluded: $foreign non-browser password records and $archives password archives. Other entry types are not included.")
            if (groups.isEmpty()) Text(text = "No compatible browser passwords to export.")
            groups.forEachIndexed { index, label -> ForestTextAction(text = label, onClick = { onSelect(index) }) }
        } }, confirmButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) })
}
