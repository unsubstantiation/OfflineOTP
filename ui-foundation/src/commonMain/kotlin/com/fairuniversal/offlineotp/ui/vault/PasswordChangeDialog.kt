package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
fun PasswordChangeDialog(inputKey: Any, busy: Boolean, result: String?, invalid: Boolean,
                         onSubmit: (String, String, String) -> Unit, onDismiss: () -> Unit,
                         maxFieldChars: Int, requiresUnlock: Boolean = true, errorText: String? = null) {
    var current by remember(inputKey) { mutableStateOf("") }
    var next by remember(inputKey) { mutableStateOf("") }
    var confirmation by remember(inputKey) { mutableStateOf("") }
    fun clear() { current = ""; next = ""; confirmation = "" }
    AlertDialog(onDismissRequest = { if (!busy) { clear(); onDismiss() } },
        title = { Text(text = "Change vault password") }, text = { ScrollableDialogBody {
            Text(text = if (requiresUnlock) "Submitting locks the vault. You must unlock again afterwards. Once started, the password change cannot be canceled; unlock and normal window close are blocked until it finishes. Do not force-quit during this operation. Existing backup files keep their own passwords."
                else "Re-encrypts the vault with a fresh random salt. Keep the application open until the change finishes. Existing backup files keep their own passwords.")
            if (busy) Text(text = "Changing password… Keep the application open.")
            else if (result != null) Text(text = result)
            else {
                RekeyField("Current vault password", current, maxFieldChars) { current = it }
                RekeyField("New vault password", next, maxFieldChars) { next = it }
                RekeyField("Confirm new password", confirmation, maxFieldChars) { confirmation = it }
                if (errorText != null) Text(text = errorText)
                else if (invalid) Text(text = "Enter the current password and a different nonempty new password with matching confirmation. Re-enter all fields to retry.")
            }
        } }, confirmButton = {
            if (result != null) ForestTextAction(text = if (requiresUnlock) "Return to unlock" else "Done", onClick = { clear(); onDismiss() })
            else ForestTextAction(text = if (requiresUnlock) "Lock and change password" else "Change vault password", enabled = !busy && current.isNotEmpty() && next.isNotEmpty() && next != current && next == confirmation,
                onClick = { val a = current; val b = next; val c = confirmation; clear(); onSubmit(a, b, c) })
        }, dismissButton = { if (!busy && result == null) ForestTextAction(text = "Cancel", onClick = { clear(); onDismiss() }) })
}

@Composable
private fun RekeyField(label: String, value: String, maxFieldChars: Int, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= maxFieldChars) change(it) },
        singleLine = true, label = { Text(text = label) }, visualTransformation = PasswordVisualTransformation())
}
