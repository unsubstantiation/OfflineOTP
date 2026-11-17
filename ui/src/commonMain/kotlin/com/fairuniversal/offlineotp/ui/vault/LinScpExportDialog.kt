package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.fairuniversal.offlineotp.Utilities.InputPolicy

const val LINSCP_PLAIN_WARNING = "Warning: Exported secrets will be accessible by software on your device."

@Composable
fun LinScpChoices(choices: List<String>, selected: Set<Int>, enabled: Boolean, change: (Set<Int>) -> Unit) {
    Text("Choose keys and sessions. Select referenced keys too if the session needs them.")
    choices.forEachIndexed { index, label ->
        Row {
            Checkbox(checked = index in selected, enabled = enabled, onCheckedChange = { checked ->
                change(if (checked) selected + index else selected - index)
            })
            Text(label)
        }
    }
}

@Composable
fun LinScpExportDialog(inputKey: Any, choices: List<String>, busy: Boolean, error: String?,
                      onSubmit: (Set<Int>, Boolean, String, String) -> Unit, onDismiss: () -> Unit) {
    var selected by remember(inputKey) { mutableStateOf<Set<Int>>(emptySet()) }
    var plainText by remember(inputKey) { mutableStateOf(false) }
    var password by remember(inputKey) { mutableStateOf("") }
    var confirmation by remember(inputKey) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Export LinSCP Key/Sessions") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LinScpChoices(choices, selected, !busy) { selected = it }
            Row {
                Checkbox(checked = plainText, enabled = !busy, onCheckedChange = { plainText = it; password = ""; confirmation = "" })
                Text("Export without a password (plain text)")
            }
            Text(LINSCP_PLAIN_WARNING)
            Text(if (plainText) "Saved as .json, never .aes. Existing files are not overwritten." else "Encrypted with Argon2id and AES-256-GCM (.json.aes).")
            if (!plainText) {
                OutlinedTextField(value = password, onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) password = it }, enabled = !busy,
                    label = { Text("Export password") }, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = confirmation, onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) confirmation = it }, enabled = !busy,
                    label = { Text("Confirm password") }, visualTransformation = PasswordVisualTransformation())
            }
            error?.let { Text(it) }
            if (busy) Text("Writing… Cancel stops pending work; a write already started may finish.")
        }
    }, confirmButton = { ForestTextAction("Export", enabled = !busy && selected.isNotEmpty() && (plainText || (password.isNotEmpty() && password == confirmation)), onClick = {
        val first = password; val second = confirmation; password = ""; confirmation = ""
        onSubmit(selected, plainText, first, second)
    }) }, dismissButton = { ForestTextAction("Cancel", onClick = onDismiss) })
}
