package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/** Only field names enter the chooser, never their secret values. */
@Composable
fun EntryCopyDialog(
    fields: List<String>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Choose what to copy",
    cancelLabel: String = "Cancel",
    message: String? = null
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = title) },
        text = { Column {
            if (!message.isNullOrEmpty()) {
                Text(text = message, style = ForestType.body, color = ForestPalette.textSecondary)
                Spacer(Modifier.height(8.dp))
            }
            fields.forEachIndexed { index, field -> ForestTextAction(text = field, onClick = { onSelect(index) }) }
        } },
        confirmButton = { ForestTextAction(text = cancelLabel, onClick = onDismiss) })
}
