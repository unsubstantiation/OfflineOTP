package com.fairuniversal.offlineotp.ui.unlock

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

/** Explicit transport and PIN handoff; no key material or transport authority belongs to Compose. */
@Composable
fun HardwareTransportDialog(busy: Boolean, error: String?, nfcAvailable: Boolean, usbAvailable: Boolean,
    onSubmit: (String, Boolean) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    fun submit(usb: Boolean) {
        if (busy || (if (usb) !usbAvailable else !nfcAvailable)) return
        val value = pin; pin = ""; onSubmit(value, usb)
    }
    AlertDialog(onDismissRequest = { pin = ""; onCancel() }, title = { Text("Unlock with enrolled security key") },
        text = { com.fairuniversal.offlineotp.ui.vault.ScrollableDialogBody {
            Text("Use the enrolled FIDO2 PRF key and verify with its PIN or built-in user verification. The PIN is not an OATH password. No automatic PIN retry is made. Password unlock remains available.")
            OutlinedTextField(value = pin, onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) pin = it },
                enabled = !busy, singleLine = true, label = { Text("FIDO PIN (optional for built-in UV)") }, visualTransformation = PasswordVisualTransformation())
            Text(if (nfcAvailable) "NFC transport available; requires a compatible PRF key." else "NFC transport unavailable on this device.")
            Text(if (usbAvailable) "USB-C smart-card transport available; key compatibility is checked on connection." else "USB-C smart-card transport unavailable on this device.")
            if (busy) Text("Waiting for security key…")
            com.fairuniversal.offlineotp.ui.vault.SharedStatusMessage(error)
            com.fairuniversal.offlineotp.ui.vault.ForestTextAction("Use NFC", enabled = !busy && nfcAvailable, onClick = { submit(false) })
            com.fairuniversal.offlineotp.ui.vault.ForestTextAction("Use USB-C", enabled = !busy && usbAvailable, onClick = { submit(true) })
        } }, confirmButton = {}, dismissButton = { com.fairuniversal.offlineotp.ui.vault.ForestTextAction("Cancel / back to password", onClick = { pin = ""; onCancel() }) })
}
