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
fun PasswordEntryDialog(inputKey: Any, initial: PasswordEntryInput, failed: Boolean,
                        onSubmit: (PasswordEntryInput) -> Unit, onDismiss: () -> Unit) {
    var input by remember(inputKey) { mutableStateOf(initial) }
    fun dismiss() { input = PasswordEntryInput(); onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(text = "Edit password") },
        text = { Column {
            Text(text = "Leave password blank to keep it. Other stored columns, including notes and GUIDs, are preserved.")
            OutlinedTextField(value = input.url, onValueChange = { if (it.length <= InputPolicy.MAX_URI_CHARS) input = input.copy(url = it) }, label = { Text(text = "URL") })
            OutlinedTextField(value = input.username, onValueChange = { if (it.length <= InputPolicy.MAX_URI_CHARS) input = input.copy(username = it) }, label = { Text(text = "Username") })
            OutlinedTextField(value = input.password, onValueChange = { if (it.length <= InputPolicy.MAX_URI_CHARS) input = input.copy(password = it) },
                label = { Text(text = "Password (blank keeps stored value)") }, visualTransformation = PasswordVisualTransformation())
            if (failed) Text(text = "Could not update the password entry. Check the fields and vault storage. Re-enter any new password to retry.")
        } },
        confirmButton = { ForestTextAction(text = "Save", onClick = {
            val submitted = input; input = input.withoutPassword(); onSubmit(submitted)
        }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) })
}
