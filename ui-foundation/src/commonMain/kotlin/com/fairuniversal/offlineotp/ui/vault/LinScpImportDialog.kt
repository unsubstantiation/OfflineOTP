package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
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

@Composable
fun LinScpImportDialog(inputKey: Any, busy: Boolean, count: Int?, failed: Boolean, keySelected: Boolean,
                       onChooseKey: () -> Unit, onClearKey: () -> Unit,
                       onRead: (String) -> Unit, onConfirm: (Set<Int>) -> Unit, onDismiss: () -> Unit, choices: List<String>,
                       secretLabel: String = "Vault password (blank for unencrypted backups)",
                       allowKeyFileSelection: Boolean = true) {
    var selected by remember(inputKey, count) { mutableStateOf<Set<Int>>(emptySet()) }
    var password by remember(inputKey) { mutableStateOf("") }
    fun dismiss() { password = ""; onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(text = "Import LinSCP Key/Sessions") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(text = "Read an encrypted vault, backup bundle or sessions.ini (maximum 16 MiB). Source and key files remain unchanged.")
            if (count == null) {
                OutlinedTextField(value = password,
                    onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) password = it }, enabled = !busy,
                    singleLine = true, label = { Text(text = secretLabel) },
                    visualTransformation = PasswordVisualTransformation())
                if (allowKeyFileSelection) {
                    Text(text = if (keySelected) "Session master-key file selected." else "Select sessions.key if this session file needs an external master key.")
                    ForestTextAction(text = "Choose sessions.key", enabled = !busy, onClick = onChooseKey)
                    if (keySelected) ForestTextAction(text = "Clear key selection", enabled = !busy, onClick = onClearKey)
                }
            }
            if (busy) Text(text = "Reading LinSCP credentials…")
            if (count != null) LinScpChoices(choices, selected, !busy) { selected = it }
            if (count != null) Text(text = "$count entries ready. Import merges them into the current vault; identical entries are skipped. No current entries are replaced.")
            if (failed) SharedStatusMessage(if (count == null)
                "Could not read the file. Check the password, key file, format and size. Re-enter the password to retry."
                else "Could not save the selected entries. Check vault capacity and storage, then retry or cancel.")
        } },
        confirmButton = { ForestTextAction(text = if (count == null) "Read file" else "Import", enabled = !busy && (count == null || selected.isNotEmpty()),
            onClick = {
                if (count != null) onConfirm(selected)
                else { val submitted = password; password = ""; onRead(submitted) }
            }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) })
}
