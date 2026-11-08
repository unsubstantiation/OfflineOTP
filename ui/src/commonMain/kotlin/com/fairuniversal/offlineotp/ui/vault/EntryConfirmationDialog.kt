package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

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
        text = { Text(text = if (error == null) message else "$message\n\n$error") },
        confirmButton = { ForestTextAction(text = confirmLabel, onClick = onConfirm) },
        dismissButton = { ForestTextAction(text = dismissLabel, onClick = onDismiss) }
    )
}
