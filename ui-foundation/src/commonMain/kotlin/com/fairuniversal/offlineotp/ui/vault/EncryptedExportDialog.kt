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
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds

/** Only collects a new backup password. Neither password nor confirmation is saveable. */
@Composable
fun EncryptedExportDialog(inputKey: Any, busy: Boolean, error: String?,
                          onSubmit: (String, String) -> Unit, onDismiss: () -> Unit,
                          onSnapshot: (() -> Unit)? = null,
                          onMigrateAndSnapshot: ((String) -> Unit)? = null,
                          title: String = "Export encrypted backup",
                          description: String = "Protect the full vault with a backup password. It cannot be recovered if forgotten. Existing files will not be overwritten.") {
    var password by remember(inputKey) { mutableStateOf("") }
    var confirmation by remember(inputKey) { mutableStateOf("") }
    var recoveryPassword by remember(inputKey) { mutableStateOf("") }
    var migrating by remember(inputKey) { mutableStateOf(false) }
    fun clear() { password = ""; confirmation = ""; recoveryPassword = "" }
    fun dismiss() { clear(); onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(text = title) },
        text = { ScrollableDialogBody {
            if (onSnapshot != null) {
                Text("Full vault copy: copies the saved encrypted vault file unchanged. Recover it using the vault recovery password, not a separate backup password. Includes all saved entries, not device settings or local unlock credentials. Legacy vaults must be migrated first.")
                ForestTextAction("Save full vault copy", enabled = !busy, onClick = {
                    clear(); onSnapshot()
                })
                if (onMigrateAndSnapshot != null) {
                    ForestTextAction("Migrate a legacy password vault first", enabled = !busy, onClick = {
                        clear(); migrating = !migrating
                    })
                    if (migrating) {
                        Text("Enter the current vault password. Migration preserves the password and entries, keeps original bytes in the local rollback file, and makes future full-file copies independently recoverable. A device-only vault must first be switched to password authentication and password encryption in Settings. Cancel stops pending work; an atomic promotion already started may finish.")
                        OutlinedTextField(value = recoveryPassword,
                            onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) recoveryPassword = it },
                            enabled = !busy, singleLine = true, label = { Text("Current vault recovery password") }, visualTransformation = PasswordVisualTransformation())
                        ForestTextAction("Migrate and save full vault copy", enabled = !busy && recoveryPassword.isNotEmpty(), onClick = {
                            val current = recoveryPassword; clear(); onMigrateAndSnapshot(current)
                        })
                    }
                }
                Text("Or create a separate password-encrypted export:")
            }
            Text(text = description)
            OutlinedTextField(value = password,
                onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) password = it }, enabled = !busy,
                singleLine = true, label = { Text(text = "Backup password") }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(value = confirmation,
                onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) confirmation = it }, enabled = !busy,
                singleLine = true, label = { Text(text = "Confirm password") }, visualTransformation = PasswordVisualTransformation())
            SharedStatusMessage(error)
            if (busy) Text(text = "Working… Cancel or lock stops pending work; a file write already started may finish.")
        } },
        confirmButton = { ForestTextAction(text = "Export", enabled = !busy && password.isNotEmpty() && password == confirmation,
            onClick = {
                val first = password; val second = confirmation
                clear()
                onSubmit(first, second)
            }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) })
}
