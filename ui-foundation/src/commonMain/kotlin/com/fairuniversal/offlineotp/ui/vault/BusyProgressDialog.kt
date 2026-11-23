package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/**
 * Shared modal busy/status dialog across Android, Desktop and iOS (for example while re-encrypting
 * the vault or waiting for a USB/NFC security key).
 *
 * When [onCancel] is null, dismissal gestures are ignored so background re-encryption cannot be
 * abandoned mid-flight; when [onCancel] is provided, cancelling notifies the host once.
 */
@Composable
fun BusyProgressDialog(
    title: String,
    message: String,
    cancelLabel: String? = null,
    onCancel: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = { onCancel?.invoke() },
        title = { Text(text = title, style = ForestType.title, color = ForestPalette.textPrimary) },
        text = { Text(text = message, style = ForestType.body, color = ForestPalette.textSecondary) },
        confirmButton = {},
        dismissButton = if (cancelLabel != null && onCancel != null) {
            { ForestTextAction(text = cancelLabel, onClick = onCancel) }
        } else null
    )
}
