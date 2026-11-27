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
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds

@Composable
fun HardwareOathDialog(
    inputKey: Any,
    labels: List<String>,
    codes: List<String?>,
    counters: List<Boolean>,
    busy: Boolean,
    message: String?,
    onSubmit: (CharArray, Int?) -> Unit,
    onDismiss: () -> Unit,
    onCopyCode: ((Int) -> Unit)? = null,
    onDelete: ((CharArray, Int) -> Unit)? = null,
    onRename: ((CharArray, Int, String, String) -> Unit)? = null
) {
    var renaming by remember(inputKey, labels) { mutableStateOf<Int?>(null) }
    var deleting by remember(inputKey, labels) { mutableStateOf<Int?>(null) }
    var pin by remember(inputKey) { mutableStateOf("") }
    fun submit(index: Int?) { val value = pin.toCharArray(); pin = ""; onSubmit(value, index) }
    val selected = deleting
    if (selected != null && onDelete != null) {
        HardwareOathDeleteDialog(inputKey = inputKey to selected, label = labels.getOrNull(selected).orEmpty(),
            busy = busy, onConfirm = { password -> deleting = null; onDelete(password, selected) },
            onDismiss = { deleting = null; pin = "" })
        return
    }
    val renameIndex = renaming
    if (renameIndex != null && onRename != null) {
        HardwareOathRenameDialog(inputKey = inputKey to renameIndex, label = labels.getOrNull(renameIndex).orEmpty(), busy = busy,
            onConfirm = { password, issuer, name -> renaming = null; onRename(password, renameIndex, issuer, name) },
            onDismiss = { renaming = null; pin = "" })
        return
    }
    AlertDialog(onDismissRequest = { pin = ""; onDismiss() }, title = { Text(text = "OATH security key") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(text = "Connect one PC/SC-compatible key. No secrets are imported into the vault. Enter the OATH password if configured (not the FIDO PIN). Calculating HOTP advances the key counter and cannot be undone. Touch the key if required. Cancellation cannot undo an already-issued device command.")
            OutlinedTextField(value = pin, onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) pin = it }, enabled = !busy,
                singleLine = true, label = { Text(text = "OATH password (optional; re-enter per operation)") }, visualTransformation = PasswordVisualTransformation())
            if (busy) Text(text = "Waiting for security key…")
            if (message != null) Text(text = message)
            if (deleting == null) labels.forEachIndexed { index, label ->
                val currentCode = codes.getOrNull(index)
                Text(text = label)
                Text(text = currentCode ?: "Hidden or expired")
                if (currentCode != null && onCopyCode != null) {
                    ForestTextAction(text = "Copy code", enabled = !busy, onClick = { onCopyCode(index) })
                }
                ForestTextAction(text = if (counters.getOrNull(index) == true) "Advance HOTP counter and show code" else "Calculate and show code",
                    enabled = !busy, onClick = { submit(index) })
                if (onRename != null) ForestTextAction("Rename this account…", enabled = !busy, onClick = { renaming = index; pin = "" })
                if (onDelete != null) ForestTextAction("Delete this account…", enabled = !busy, onClick = { deleting = index; pin = "" })
            }
        } }, confirmButton = { ForestTextAction(text = "Read / refresh accounts", enabled = !busy && deleting == null, onClick = { submit(null) }) },
        dismissButton = { ForestTextAction(text = "Close", onClick = { pin = ""; onDismiss() }) })
}

/** Same explicit, account-bound destructive confirmation on Android and desktop. */
@Composable
fun HardwareOathDeleteDialog(inputKey: Any, label: String, busy: Boolean = false,
                             onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var accepting by remember(inputKey) { mutableStateOf(true) }
    var confirmation by remember(inputKey) { mutableStateOf("") }
    var pin by remember(inputKey) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { accepting = false; pin = ""; confirmation = ""; onDismiss() },
        title = { Text("Delete security-key account") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text("Permanently delete $label from this security key? This cannot be undone. The vault entry is not removed. Make sure you have another way to sign in. A started deletion may finish after cancellation; read the key before retrying any uncertain result.")
            OutlinedTextField(value = pin, onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) pin = it },
                enabled = !busy, singleLine = true, label = { Text("OATH password (re-enter if configured; not FIDO PIN)") },
                visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(value = confirmation, onValueChange = { if (it.length <= 6) confirmation = it },
                enabled = !busy, singleLine = true, label = { Text("Type DELETE to confirm") })
        } }, confirmButton = {
            ForestTextAction("Permanently delete account", enabled = !busy && confirmation == "DELETE", onClick = {
                if (accepting && !busy && confirmation == "DELETE") {
                    accepting = false
                    val password = pin.toCharArray(); pin = ""; confirmation = ""; onConfirm(password)
                }
            })
        }, dismissButton = { ForestTextAction("Cancel", onClick = { accepting = false; pin = ""; confirmation = ""; onDismiss() }) })
}


@Composable
fun HardwareOathRenameDialog(inputKey: Any, label: String, busy: Boolean = false,
                             onConfirm: (CharArray, String, String) -> Unit, onDismiss: () -> Unit) {
    var accepting by remember(inputKey) { mutableStateOf(true) }
    var issuer by remember(inputKey) { mutableStateOf("") }
    var name by remember(inputKey) { mutableStateOf("") }
    var confirmation by remember(inputKey) { mutableStateOf("") }
    var pin by remember(inputKey) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { accepting = false; pin = ""; onDismiss() }, title = { Text("Rename security-key account") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text("Rename $label on the key only. Enter the full new account name and optional issuer; blank issuer removes it. Secret, counter, period and vault are unchanged. Requires a key supporting OATH rename (YubiKey 5.3 or later). Existing account IDs are never overwritten. Cancellation cannot undo an issued command; read the key before retrying an uncertain result.")
            OutlinedTextField(value = issuer, onValueChange = { if (it.length <= 64) issuer = it }, enabled = !busy,
                singleLine = true, label = { Text("New issuer (optional)") })
            OutlinedTextField(value = name, onValueChange = { if (it.length <= 64) name = it }, enabled = !busy,
                singleLine = true, label = { Text("New account name") })
            OutlinedTextField(value = pin, onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) pin = it },
                enabled = !busy, singleLine = true, label = { Text("OATH password (re-enter if configured)") },
                visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(value = confirmation, onValueChange = { if (it.length <= 6) confirmation = it }, enabled = !busy,
                singleLine = true, label = { Text("Type RENAME to confirm") })
        } }, confirmButton = {
            ForestTextAction("Rename on key", enabled = !busy && name.isNotEmpty() && confirmation == "RENAME", onClick = {
                if (accepting && !busy && name.isNotEmpty() && confirmation == "RENAME") {
                    accepting = false
                    val password = pin.toCharArray(); pin = ""; confirmation = ""; onConfirm(password, issuer, name)
                }
            })
        }, dismissButton = { ForestTextAction("Cancel", onClick = { accepting = false; pin = ""; onDismiss() }) })
}
