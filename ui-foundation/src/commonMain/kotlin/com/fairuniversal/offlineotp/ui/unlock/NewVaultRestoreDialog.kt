package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** First-run full restore: decrypt a portable backup, then protect the new local vault with a new password. */
@Composable
fun NewVaultRestoreDialog(
    backupName: String?,
    busy: Boolean,
    error: String?,
    onChooseBackup: () -> Unit,
    allowChooseBackup: Boolean = true,
    onRestore: (backupPassword: String, newVaultPassword: String, confirmation: String) -> Unit,
    onDismiss: () -> Unit
) {
    var backupPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Restore a vault from backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Choose an OfflineOTP encrypted backup, enter its backup password, then choose a new password for this machine's vault. This restores all backup entries into a new vault; no merge choice is needed.")
                if (allowChooseBackup) TextButton(onClick = onChooseBackup, enabled = !busy) {
                    Text("Choose encrypted backup…")
                }
                Text(backupName ?: "No backup selected", modifier = Modifier.padding(vertical = 6.dp))
                OutlinedTextField(backupPassword, { backupPassword = it }, enabled = !busy,
                    label = { Text("Backup password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newPassword, { newPassword = it }, enabled = !busy,
                    label = { Text("New vault password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(confirmation, { confirmation = it }, enabled = !busy,
                    label = { Text("Confirm new vault password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (error != null) Text(error, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = {
            val backup = backupPassword; val password = newPassword; val confirm = confirmation
            backupPassword = ""; newPassword = ""; confirmation = ""
            onRestore(backup, password, confirm)
        }, enabled = !busy && backupName != null && backupPassword.isNotEmpty()
            && newPassword.isNotEmpty() && newPassword == confirmation) { Text("Restore") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(if (busy) "Restoring…" else "Cancel") } }
    )
}
