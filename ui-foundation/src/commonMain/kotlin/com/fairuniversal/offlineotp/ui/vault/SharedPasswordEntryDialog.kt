package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds
import com.fairuniversal.offlineotp.ui.portable.PortablePasswordEditDraft

/**
 * Shared browser/common password entry editor across Android, Desktop and iOS.
 *
 * Never preloads a stored password; clears any typed password from local Compose state before
 * invoking [onSubmit] or [onDismiss].
 */
@Composable
fun SharedPasswordEntryDialog(
    inputKey: Any,
    initial: PortablePasswordEditDraft,
    failed: Boolean,
    onSubmit: (PortablePasswordEditDraft) -> Unit,
    onDismiss: () -> Unit,
    maxFieldChars: Int = PortableInputBounds.MAX_PASSWORD_FIELD_CHARS,
    errorText: String? = null
) {
    var revealPassword by remember(inputKey) { mutableStateOf(false) }
    var input by remember(inputKey) { mutableStateOf(initial) }
    fun dismiss() {
        input = PortablePasswordEditDraft()
        revealPassword = false
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = "Edit password") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text = "Leave password blank to keep it. Other stored columns, including notes and GUIDs, are preserved.")
                OutlinedTextField(
                    value = input.url,
                    onValueChange = { if (it.length <= maxFieldChars) input = input.copy(url = it) },
                    label = { Text(text = "URL") }
                )
                OutlinedTextField(
                    value = input.username,
                    onValueChange = { if (it.length <= maxFieldChars) input = input.copy(username = it) },
                    label = { Text(text = "Username") }
                )
                OutlinedTextField(
                    value = input.password,
                    onValueChange = { if (it.length <= maxFieldChars) input = input.copy(password = it) },
                    label = { Text(text = "Password (blank keeps stored value)") },
                    visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation()
                )
                ForestTextAction(
                    text = if (revealPassword) "Hide password" else "Show password",
                    onClick = { revealPassword = !revealPassword }
                )
                if (errorText != null) {
                    Text(text = errorText)
                } else if (failed) {
                    Text(text = "Could not update the password entry. Check the fields and vault storage. Re-enter any new password to retry.")
                }
            }
        },
        confirmButton = {
            ForestTextAction(text = "Save", onClick = {
                val submitted = input
                input = input.withoutPassword()
                revealPassword = false
                onSubmit(submitted)
            })
        },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) }
    )
}
