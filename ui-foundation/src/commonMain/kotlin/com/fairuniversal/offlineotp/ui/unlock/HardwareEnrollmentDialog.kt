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
fun HardwareEnrollmentDialog(
    inputKey: Any,
    busy: Boolean,
    result: String?,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Enroll hardware unlock",
    primaryWarning: String = "Connect only the intended FIDO2 PRF-capable PC/SC key. This creates a credential on that key and REPLACES this data directory's local unlock record. Keep your vault password and a verified backup. Copies of an old record elsewhere are not revoked.",
    secondaryWarning: String? = "Submitting locks the vault. Unlock and normal window close are blocked until completion. Do not force-quit. Cancellation or failure cannot undo an authenticator credential already created; inspect the result before retrying. You must unlock again afterwards.",
    pinLabel: String = "FIDO PIN (optional; not OATH password)",
    busyLabel: String = "Enrolling… Touch the key when prompted.",
    submitLabel: String = "Lock and replace local enrollment",
    cancelLabel: String = "Cancel",
    doneLabel: String = "Return to unlock"
) {
    var pin by remember(inputKey) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) { pin = ""; onDismiss() } }, title = { Text(text = title) },
        text = { Column {
            Text(text = primaryWarning)
            if (!secondaryWarning.isNullOrEmpty()) Text(text = secondaryWarning)
            if (busy) Text(text = busyLabel)
            else if (result != null) Text(text = result)
            else OutlinedTextField(value = pin, onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) pin = it },
                singleLine = true, label = { Text(text = pinLabel) }, visualTransformation = PasswordVisualTransformation())
        } }, confirmButton = {
            if (result != null) TextButton(onClick = { pin = ""; onDismiss() }) { Text(text = doneLabel) }
            else TextButton(enabled = !busy, onClick = {
                val submitted = pin.toCharArray(); pin = ""; onSubmit(submitted)
            }) { Text(text = submitLabel) }
        }, dismissButton = { if (!busy && result == null) TextButton(onClick = { pin = ""; onDismiss() }) { Text(text = cancelLabel) } })
}
