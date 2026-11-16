package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** The host supplies embedded build metadata; never derive a version from the runtime clock. */
@Composable
fun AboutDialog(version: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About OfflineOTP") },
        text = { Text("Version: $version\nBuild time: AKST (UTC−09:00)") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
