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
fun OpenPgpImportDialog(inputKey: Any, busy: Boolean, count: Int?, failed: Boolean, keySelected: Boolean,
                       onChooseKey: () -> Unit, onClearKey: () -> Unit,
                       onRead: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var password by remember(inputKey) { mutableStateOf("") }
    fun dismiss() { password = ""; onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(text = "Import OpenPGP backup") },
        text = { Column {
            Text(text = "Read an OpenPGP-encrypted OfflineOTP backup with a secret-key file. Message and key files are limited to 16 MiB each and remain unchanged.")
            if (count == null) {
                OutlinedTextField(value = password,
                    onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) password = it }, enabled = !busy,
                    singleLine = true, label = { Text(text = "Key passphrase (blank for unprotected keys)") },
                    visualTransformation = PasswordVisualTransformation())
                Text(text = if (keySelected) "Secret-key file selected." else "Select the OpenPGP secret-key file needed to decrypt this backup.")
                ForestTextAction(text = "Choose secret-key file", enabled = !busy, onClick = onChooseKey)
                if (keySelected) ForestTextAction(text = "Clear key selection", enabled = !busy, onClick = onClearKey)
            }
            if (busy) Text(text = "Decrypting OpenPGP backup…")
            if (count != null) Text(text = "$count entries ready. Import merges them into the current vault; identical entries are skipped. No current entries are replaced.")
            if (failed) Text(text = "Could not import the file. Check the passphrase, key file, format, size, vault capacity and storage. Re-enter the passphrase to retry.")
        } },
        confirmButton = { ForestTextAction(text = if (count == null) "Read file" else "Import", enabled = !busy && (count != null || keySelected),
            onClick = {
                if (count != null) onConfirm()
                else { val submitted = password; password = ""; onRead(submitted) }
            }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) })
}
