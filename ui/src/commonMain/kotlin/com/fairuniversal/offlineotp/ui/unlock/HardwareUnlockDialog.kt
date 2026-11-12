package com.fairuniversal.offlineotp.ui.unlock

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
import com.fairuniversal.offlineotp.ui.vault.ForestTextAction

@Composable
fun HardwareUnlockDialog(busy: Boolean, error: String?, onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) { pin = ""; onDismiss() } }, title = { Text(text = "Unlock with security key") },
        text = { Column {
            Text(text = "Connect the FIDO2 PRF key enrolled for this data directory. PC/SC transport is required. Enter its FIDO PIN if needed and touch the key when prompted. This does not enroll a new key; password unlock remains available.")
            OutlinedTextField(value = pin, onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) pin = it }, enabled = !busy,
                singleLine = true, label = { Text(text = "FIDO PIN (optional)") }, visualTransformation = PasswordVisualTransformation())
            if (busy) Text(text = "Waiting for security key…")
            if (error != null) Text(text = error)
        } }, confirmButton = { ForestTextAction(text = "Unlock with enrolled key", enabled = !busy, onClick = {
            val submitted = pin.toCharArray(); pin = ""; onSubmit(submitted)
        }) }, dismissButton = { ForestTextAction(text = "Back to password", enabled = !busy, onClick = { pin = ""; onDismiss() }) })
}
