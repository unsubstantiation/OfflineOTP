package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Export disclosure shared by all hosts. Only Android can opt in to a saved backup password.
 * Hosts revalidate the live session before serialising settings or opening a native picker.
 */
@Composable
fun PreferencesExportDialog(
    inputKey: Any,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    allowBackupPassword: Boolean = false,
    title: String = "Export preferences",
    message: String = "Exports portable settings for this platform only. Vault entries, entry passwords and vault authentication material are never included. The file is not encrypted; store it safely.",
    backupPasswordLabel: String = "Include saved backup password (plaintext)",
    confirmLabel: String = "Choose destination",
    dismissLabel: String = "Cancel",
    errorText: String? = null,
    detail: String = ""
) {
    var includeBackupPassword by remember(inputKey) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { includeBackupPassword = false; onDismiss() },
        title = { Text(text = title) },
        text = {
            ScrollableDialogBody {
                Text(text = message)
                if (detail.isNotEmpty()) Text(text = detail)
                if (allowBackupPassword) {
                    Row {
                        Checkbox(checked = includeBackupPassword, onCheckedChange = { includeBackupPassword = it })
                        Text(text = backupPasswordLabel)
                    }
                }
                SharedStatusMessage(errorText)
            }
        },
        confirmButton = {
            ForestTextAction(text = confirmLabel, onClick = {
                val include = allowBackupPassword && includeBackupPassword
                includeBackupPassword = false
                onConfirm(include)
            })
        },
        dismissButton = {
            ForestTextAction(text = dismissLabel, onClick = { includeBackupPassword = false; onDismiss() })
        }
    )
}
