package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Only field names enter the chooser, never their secret values. */
@Composable
fun EntryCopyDialog(fields: List<String>, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Choose what to copy") },
        text = { Column {
            fields.forEachIndexed { index, field -> ForestTextAction(text = field, onClick = { onSelect(index) }) }
        } },
        confirmButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) })
}
