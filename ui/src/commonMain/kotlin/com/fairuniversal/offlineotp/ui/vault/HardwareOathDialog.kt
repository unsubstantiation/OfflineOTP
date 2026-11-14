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
fun HardwareOathDialog(inputKey: Any, labels: List<String>, codes: List<String?>, counters: List<Boolean>, busy: Boolean,
                       message: String?, onSubmit: (CharArray, Int?) -> Unit, onDismiss: () -> Unit) {
    var pin by remember(inputKey) { mutableStateOf("") }
    fun submit(index: Int?) { val value = pin.toCharArray(); pin = ""; onSubmit(value, index) }
    AlertDialog(onDismissRequest = { pin = ""; onDismiss() }, title = { Text(text = "OATH security key") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(text = "Connect one PC/SC-compatible key. No secrets are imported into the vault. Enter the OATH password if configured (not the FIDO PIN). Calculating HOTP advances the key counter and cannot be undone. Touch the key if required. Cancellation cannot undo an already-issued device command.")
            OutlinedTextField(value = pin, onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) pin = it }, enabled = !busy,
                singleLine = true, label = { Text(text = "OATH password (optional; re-enter per operation)") }, visualTransformation = PasswordVisualTransformation())
            if (busy) Text(text = "Waiting for security key…")
            if (message != null) Text(text = message)
            labels.forEachIndexed { index, label ->
                Text(text = label)
                Text(text = codes.getOrNull(index) ?: "Hidden or expired")
                ForestTextAction(text = if (counters.getOrNull(index) == true) "Advance HOTP counter and show code" else "Calculate and show code",
                    enabled = !busy, onClick = { submit(index) })
            }
        } }, confirmButton = { ForestTextAction(text = "Read / refresh accounts", enabled = !busy, onClick = { submit(null) }) },
        dismissButton = { ForestTextAction(text = "Close", onClick = { pin = ""; onDismiss() }) })
}
