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

/** Only collects a new backup password. Neither password nor confirmation is saveable. */
@Composable
fun EncryptedExportDialog(inputKey: Any, busy: Boolean, error: String?,
                          onSubmit: (String, String) -> Unit, onDismiss: () -> Unit,
                          title: String = "Export encrypted backup",
                          description: String = "Protect the full vault with a backup password. It cannot be recovered if forgotten. Existing files will not be overwritten.") {
    var password by remember(inputKey) { mutableStateOf("") }
    var confirmation by remember(inputKey) { mutableStateOf("") }
    fun dismiss() { password = ""; confirmation = ""; onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(text = title) },
        text = { Column {
            Text(text = description)
            OutlinedTextField(value = password,
                onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) password = it }, enabled = !busy,
                singleLine = true, label = { Text(text = "Backup password") }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(value = confirmation,
                onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) confirmation = it }, enabled = !busy,
                singleLine = true, label = { Text(text = "Confirm password") }, visualTransformation = PasswordVisualTransformation())
            if (error != null) Text(text = error)
            if (busy) Text(text = "Encrypting… Cancel or lock stops pending work; a file write already started may finish.")
        } },
        confirmButton = { ForestTextAction(text = "Export", enabled = !busy && password.isNotEmpty() && password == confirmation,
            onClick = {
                val first = password; val second = confirmation
                password = ""; confirmation = ""
                onSubmit(first, second)
            }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) })
}
