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
import com.fairuniversal.offlineotp.Utilities.EmailMaterial

@Composable
fun EmailPasswordDialog(inputKey: Any, initial: EmailPasswordInput, failed: Boolean,
                        onSubmit: (EmailPasswordInput) -> Unit, onDismiss: () -> Unit) {
    var input by remember(inputKey) { mutableStateOf(initial) }
    fun dismiss() { input = input.withoutSecrets(); onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(text = "Change email passwords / tokens") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(text = "Blank keeps the stored credential. SMTP numbers follow the connection details order. Server settings, authentication types and all identities are preserved. This does not change passwords at your provider or refresh OAuth tokens.")
            EmailPasswordField("Incoming password / token", input.incoming) { input = input.copy(incoming = it) }
            input.outgoing.forEachIndexed { index, password ->
                EmailPasswordField("Outgoing SMTP #${index + 1} password / token", password) { next ->
                    input = input.copy(outgoing = input.outgoing.mapIndexed { i, value -> if (i == index) next else value })
                }
            }
            if (failed) Text(text = "Could not save credentials. Original account retained. Re-enter changes to retry.")
        } }, confirmButton = { ForestTextAction(text = "Save", onClick = {
            val submitted = input; input = input.withoutSecrets(); onSubmit(submitted)
        }) }, dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) })
}

@Composable
private fun EmailPasswordField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= EmailMaterial.MAX_CREDENTIAL_CHARS) change(it) },
        singleLine = true, label = { Text(text = label) }, visualTransformation = PasswordVisualTransformation())
}
