package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Shared preference shell; a host may supply a platform-protected input without exposing text here. */
@Composable
fun ConfirmedSecretPreferenceDialog(title: String, description: String, confirmLabel: String, dismissLabel: String,
                                   valid: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit,
                                   credentialInput: @Composable () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { ScrollableDialogBody { Text(description); credentialInput() } },
        confirmButton = { ForestTextAction(text = confirmLabel, enabled = valid, onClick = onConfirm) },
        dismissButton = { ForestTextAction(text = dismissLabel, onClick = onDismiss) })
}
