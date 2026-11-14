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
fun HardwareEnrollmentDialog(inputKey: Any, busy: Boolean, result: String?, onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var pin by remember(inputKey) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) { pin = ""; onDismiss() } }, title = { Text(text = "Enroll hardware unlock") },
        text = { Column {
            Text(text = "Connect only the intended FIDO2 PRF-capable PC/SC key. This creates a credential on that key and REPLACES this data directory's local unlock record. Keep your vault password and a verified backup. Copies of an old record elsewhere are not revoked.")
            Text(text = "Submitting locks the vault. Unlock and normal window close are blocked until completion. Do not force-quit. Cancellation or failure cannot undo an authenticator credential already created; inspect the result before retrying. You must unlock again afterwards.")
            if (busy) Text(text = "Enrolling… Touch the key when prompted.")
            else if (result != null) Text(text = result)
            else OutlinedTextField(value = pin, onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) pin = it },
                singleLine = true, label = { Text(text = "FIDO PIN (optional; not OATH password)") }, visualTransformation = PasswordVisualTransformation())
        } }, confirmButton = {
            if (result != null) ForestTextAction(text = "Return to unlock", onClick = { pin = ""; onDismiss() })
            else ForestTextAction(text = "Lock and replace local enrollment", enabled = !busy, onClick = {
                val submitted = pin.toCharArray(); pin = ""; onSubmit(submitted)
            })
        }, dismissButton = { if (!busy && result == null) ForestTextAction(text = "Cancel", onClick = { pin = ""; onDismiss() }) })
}
