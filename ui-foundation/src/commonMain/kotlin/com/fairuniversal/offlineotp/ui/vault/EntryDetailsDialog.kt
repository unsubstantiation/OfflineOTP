package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Shared read-only entry detail dialog. Nothing is stored in saved state; dismissing or
 * detaching the host drops the rendered lines with the composition.
 */
@Composable
fun EntryDetailsDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    actions: List<String> = emptyList(),
    onAction: ((Int) -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            ScrollableDialogBody {
                if (body.isNotEmpty()) {
                    Text(text = body)
                }
                if (onAction != null) {
                    actions.forEachIndexed { index, label ->
                        ForestTextAction(text = label, onClick = { onAction(index) })
                    }
                }
            }
        },
        confirmButton = { ForestTextAction(text = confirmLabel, onClick = onDismiss) }
    )
}
