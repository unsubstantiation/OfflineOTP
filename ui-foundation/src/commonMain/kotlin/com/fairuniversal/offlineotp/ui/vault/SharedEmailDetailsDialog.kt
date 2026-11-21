package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fairuniversal.offlineotp.ui.portable.PortableDetailRow
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds

/**
 * Shared read-only FairEmail connection details dialog across Android, Desktop and iOS.
 *
 * Host supplies current [rows] only; no remembered, saveable or selectable credential copies.
 */
@Composable
fun SharedEmailDetailsDialog(
    rows: List<PortableDetailRow>?,
    secretsVisible: Boolean,
    onToggleSecrets: () -> Unit,
    onDismiss: () -> Unit,
    onEditPasswords: (() -> Unit)? = null,
    onEditSettings: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Email connection details") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = PortableInputBounds.EMAIL_EDIT_WARNING)
                if (rows == null) {
                    Text(text = "Could not read the email account details.")
                } else {
                    for (row in rows) {
                        Text(text = row.label)
                        Text(text = row.value)
                    }
                }
            }
        },
        confirmButton = {
            Column {
                if (rows != null && onEditSettings != null) {
                    ForestTextAction(text = "Edit connection settings", onClick = onEditSettings)
                }
                if (rows != null && onEditPasswords != null) {
                    ForestTextAction(text = "Change passwords / tokens", onClick = onEditPasswords)
                }
                if (rows != null) {
                    ForestTextAction(
                        text = if (secretsVisible) "Hide passwords" else "Show passwords",
                        onClick = onToggleSecrets
                    )
                }
            }
        },
        dismissButton = { ForestTextAction(text = "Close", onClick = onDismiss) }
    )
}
