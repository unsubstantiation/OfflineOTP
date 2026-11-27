package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Portable-preferences import confirmation, shared by every Compose host.
 *
 * The host validates the picked file before showing this dialog and supplies the plan counts;
 * [onConfirm] applies it. [platformName] and [applyDetail] name the importing client, because a
 * preferences file is addressed to one platform and the settings it can carry differ per client.
 * [failureText] overrides the generic failure line when the host can name the problem safely.
 */
@Composable
fun PreferencesImportDialog(busy: Boolean, count: Int?, skipped: Int, failed: Boolean,
                            onConfirm: () -> Unit, onDismiss: () -> Unit,
                            platformName: String = "desktop",
                            applyDetail: String = "Apply updates the current sort, card layout and auto-lock timeout when included. A file may disable auto-lock; import only preferences you trust.",
                            failureText: String? = null) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Import preferences") },
        text = { ScrollableDialogBody {
            Text(text = "Only $platformName portable settings are imported. Vault entries, passwords and authentication material are not changed. Settings absent from the file keep their current values.")
            if (busy) Text(text = "Reading preferences…")
            if (count != null) {
                Text(text = "$count settings ready; $skipped unknown or disallowed settings skipped.")
                Text(text = applyDetail)
            }
            if (failed) SharedStatusMessage(failureText ?: "Could not import preferences. Check the file's $platformName format, size and storage. Cancel to select another file.")
        } },
        confirmButton = { ForestTextAction(text = "Apply settings", enabled = !busy && count != null && !failed, onClick = onConfirm) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) })
}
