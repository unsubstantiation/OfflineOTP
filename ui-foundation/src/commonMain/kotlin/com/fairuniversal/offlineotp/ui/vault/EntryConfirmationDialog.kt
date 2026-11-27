package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

/**
 * Shared entry-action confirmation chrome. The host owns the pending action and validates its
 * stable entry id against the live session on confirmation. No entry, key or secret is retained
 * here; removing the pending action from composition dismisses the dialog (including on lock).
 * All wording comes from the caller so Android can use its localized resources.
 */
@Composable
fun EntryConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    error: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { androidx.compose.foundation.layout.Column {
            Text(text = message)
            SharedStatusMessage(error)
        } },
        confirmButton = { ForestTextAction(text = confirmLabel, onClick = onConfirm) },
        dismissButton = { ForestTextAction(text = dismissLabel, onClick = onDismiss) }
    )
}

/** Shared two-button confirmation dialog with an explicit boolean option toggle. */
@Composable
fun CheckboxConfirmationDialog(
    inputKey: Any,
    title: String,
    message: String,
    checkboxLabel: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    initialChecked: Boolean = false
) {
    var checked by remember(inputKey) { mutableStateOf(initialChecked) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                Text(text = message)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Text(text = checkboxLabel)
                }
            }
        },
        confirmButton = { ForestTextAction(text = confirmLabel, onClick = { onConfirm(checked) }) },
        dismissButton = { ForestTextAction(text = dismissLabel, onClick = onDismiss) }
    )
}
