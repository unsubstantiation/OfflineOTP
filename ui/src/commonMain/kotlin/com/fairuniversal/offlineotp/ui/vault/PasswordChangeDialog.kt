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
fun PasswordChangeDialog(inputKey: Any, busy: Boolean, result: String?, invalid: Boolean,
                         onSubmit: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var current by remember(inputKey) { mutableStateOf("") }
    var next by remember(inputKey) { mutableStateOf("") }
    var confirmation by remember(inputKey) { mutableStateOf("") }
    fun clear() { current = ""; next = ""; confirmation = "" }
    AlertDialog(onDismissRequest = { if (!busy) { clear(); onDismiss() } },
        title = { Text(text = "Change vault password") }, text = { Column {
            Text(text = "Submitting locks the vault. You must unlock again afterwards. Once started, the password change cannot be canceled; unlock and normal window close are blocked until it finishes. Do not force-quit during this operation. Existing backup files keep their own passwords.")
            if (busy) Text(text = "Changing password… Keep the application open.")
            else if (result != null) Text(text = result)
            else {
                RekeyField("Current vault password", current) { current = it }
                RekeyField("New vault password", next) { next = it }
                RekeyField("Confirm new password", confirmation) { confirmation = it }
                if (invalid) Text(text = "Enter the current password and a different nonempty new password with matching confirmation. Re-enter all fields to retry.")
            }
        } }, confirmButton = {
            if (result != null) ForestTextAction(text = "Return to unlock", onClick = { clear(); onDismiss() })
            else ForestTextAction(text = "Lock and change password", enabled = !busy && current.isNotEmpty() && next.isNotEmpty() && next == confirmation,
                onClick = { val a = current; val b = next; val c = confirmation; clear(); onSubmit(a, b, c) })
        }, dismissButton = { if (!busy && result == null) ForestTextAction(text = "Cancel", onClick = { clear(); onDismiss() }) })
}

@Composable
private fun RekeyField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) change(it) },
        singleLine = true, label = { Text(text = label) }, visualTransformation = PasswordVisualTransformation())
}
