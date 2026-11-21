package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Plain text only: paths are not URLs, markup, shell commands or automatic clipboard payloads. */
@Composable
fun StorageLocationDialog(directory: String, filenames: List<String>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Portable storage location") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(text = "Current data directory (opened at startup)")
            Text(text = directory)
            Text(text = "Managed filenames (not all files necessarily exist)")
            filenames.forEach { Text(text = it) }
            Text(text = "The data folder contains the encrypted vault and backup, preferences, and any hardware-unlock record. Keep the folder private. External exports may contain plaintext secrets and are not listed here.")
            Text(text = "To relocate: close all OfflineOTP instances, keep a separate verified backup, and copy the entire portable installation or data folder to the intended location. Do not move or replace an active vault, and do not open two clients against the same data directory.")
            Text(text = "For a custom location, set -Dofflineotp.home=<program-folder> or OFFLINEOTP_HOME before launch. The data subfolder is appended automatically; do not point these at the data subfolder itself. The system property takes precedence. Restart to apply changes. This dialog does not move files or change configuration.")
        } }, confirmButton = { ForestTextAction(text = "Close", onClick = onDismiss) })
}
