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
fun EmailImportDialog(inputKey: Any, busy: Boolean, count: Int?, failed: Boolean,
                      onRead: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var password by remember(inputKey) { mutableStateOf("") }
    fun dismiss() { password = ""; onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(text = "Import FairEmail backup") },
        text = { Column {
            Text(text = "Import accounts and their SMTP identities from a FairEmail JSON, gzip or encrypted backup. The source file is not changed or deleted. Maximum file size: 8 MiB.")
            if (count == null) OutlinedTextField(value = password,
                onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) password = it }, enabled = !busy,
                singleLine = true, label = { Text(text = "Backup password (blank for unencrypted files)") },
                visualTransformation = PasswordVisualTransformation())
            if (busy) Text(text = "Reading FairEmail backup…")
            if (count != null) Text(text = "$count accounts ready. Import merges them into this vault, skipping identical accounts. Current entries are not replaced.")
            if (failed) Text(text = "Could not import the backup. Check the password, format, file size, vault capacity and storage. Re-enter the password to retry.")
        } },
        confirmButton = { ForestTextAction(text = if (count == null) "Read backup" else "Import", enabled = !busy,
            onClick = {
                if (count != null) onConfirm()
                else { val submitted = password; password = ""; onRead(submitted) }
            }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) })
}
