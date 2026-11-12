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
import com.fairuniversal.offlineotp.Utilities.InputPolicy

@Composable
fun HardwareCopyDialog(inputKey: Any, labels: List<String>, selected: Int, selecting: Boolean, confirming: Boolean,
                       message: String?, onSelect: (Int) -> Unit, onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var pin by remember(inputKey) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { pin = ""; onDismiss() }, title = { Text(text = "Copy OTP seed to security key") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (selecting) {
                Text(text = "Choose a compatible TOTP/HOTP entry. Hidden and filtered-out entries are included. Other types or unsupported names/parameters are omitted.")
                if (labels.isEmpty()) Text(text = "No compatible entries.")
                labels.forEachIndexed { index, label -> ForestTextAction(text = label, onClick = { onSelect(index) }) }
            } else {
                Text(text = labels.getOrNull(selected).orEmpty())
                Text(text = "This exports the secret seed to the first connected PC/SC OATH key. Connect only the intended key. Touch is required for future calculations. Existing hardware credentials with the same ID are refused, not overwritten. The vault copy remains. HOTP copies start at the stored counter and can diverge if both copies are used. Cancellation cannot undo an issued device write.")
                if (confirming) OutlinedTextField(value = pin, onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) pin = it },
                    singleLine = true, label = { Text(text = "OATH password (optional; not FIDO PIN)") }, visualTransformation = PasswordVisualTransformation())
            }
            if (message != null) Text(text = message)
        } }, confirmButton = {
            if (confirming) ForestTextAction(text = "Export seed to connected key", onClick = { val value = pin.toCharArray(); pin = ""; onSubmit(value) })
        }, dismissButton = { ForestTextAction(text = "Close", onClick = { pin = ""; onDismiss() }) })
}
