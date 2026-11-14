package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.fairuniversal.offlineotp.Utilities.InputPolicy

@Composable
fun EncryptedRestoreDialog(inputKey: Any, busy: Boolean, count: Int?, failed: Boolean,
                           onDecode: (String) -> Unit, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var password by remember(inputKey) { mutableStateOf("") }
    var replace by remember(inputKey, count) { mutableStateOf(false) }
    fun dismiss() { password = ""; onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(text = "Restore encrypted backup") },
        text = { Column {
            if (count == null) {
                Text(text = "Open a password-encrypted OfflineOTP backup. You will confirm before entries are changed.")
                OutlinedTextField(value = password,
                    onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) password = it },
                    enabled = !busy, singleLine = true, label = { Text(text = "Backup password") },
                    visualTransformation = PasswordVisualTransformation())
            } else {
                Text(text = "Backup contains $count entries. Merge keeps current accounts and their metadata; only new accounts are added.")
                ForestTextAction(text = if (!replace) "Selected: Merge" else "Merge", onClick = { replace = false })
                ForestTextAction(text = if (replace) "Selected: Replace all current entries" else "Replace all current entries", onClick = { replace = true })
                if (replace) Text(text = "Warning: Restore will delete all current entries and replace them with this backup.")
            }
            if (busy) Text(text = "Decrypting…")
            if (failed) Text(text = "Could not restore the backup. Check the password, file and vault storage, then try again.")
        } },
        confirmButton = { ForestTextAction(text = if (count == null) "Open backup" else if (replace) "Replace all entries" else "Merge entries",
            enabled = !busy && (count != null || password.isNotEmpty()), onClick = {
                if (count != null) onConfirm(replace)
                else { val text = password; password = ""; onDecode(text) }
            }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) })
}
