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

/**
 * Masked, bounded PIN input. The caller owns the pending stable id and removes this dialog on
 * lock. Input is deliberately not saveable, is reset when the target changes, and is cleared before
 * submission/dismissal. Strings cannot be zeroized; clearing drops the UI's reference only.
 * The host validates the live entry and reports persistence errors without echoing the PIN.
 */
@Composable
fun EntryPinDialog(
    entryId: Long,
    title: String,
    inputLabel: String,
    confirmLabel: String,
    dismissLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
    inputKey: Any = entryId
) {
    var pin by remember(inputKey) { mutableStateOf("") }
    fun dismiss() { pin = ""; onDismiss() }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= InputPolicy.MAX_TAG_CHARS) pin = it },
                    label = { Text(text = inputLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (error != null) Text(text = error)
            }
        },
        confirmButton = {
            ForestTextAction(text = confirmLabel, enabled = pin.isNotEmpty(), onClick = {
                val submitted = pin
                pin = ""
                onSubmit(submitted)
            })
        },
        dismissButton = { ForestTextAction(text = dismissLabel, onClick = { dismiss() }) }
    )
}
