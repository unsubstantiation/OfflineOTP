package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds

@Composable
fun HardwareUnlockDialog(
    busy: Boolean,
    error: String?,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    inputKey: Any = Unit,
    transportNotice: String = "Connect the FIDO2 PRF key enrolled for this data directory. PC/SC transport is required. Enter its FIDO PIN if needed and touch the key when prompted. This does not enroll a new key; password unlock remains available.",
    pinLabel: String = "FIDO PIN (optional)",
    confirmLabel: String = "Unlock with enrolled key",
    dismissLabel: String = "Back to password"
) {
    var pin by remember(inputKey) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) { pin = ""; onDismiss() } }, title = { Text(text = "Unlock with security key") },
        text = { Column {
            Text(text = transportNotice)
            OutlinedTextField(value = pin, onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) pin = it }, enabled = !busy,
                singleLine = true, label = { Text(text = pinLabel) }, visualTransformation = PasswordVisualTransformation())
            if (busy) Text(text = "Waiting for security key…")
            com.fairuniversal.offlineotp.ui.vault.SharedStatusMessage(error)
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            val submitted = pin.toCharArray(); pin = ""; onSubmit(submitted)
        }) { Text(text = confirmLabel) } }, dismissButton = { TextButton(enabled = !busy, onClick = { pin = ""; onDismiss() }) { Text(text = dismissLabel) } })
}
