package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * Masked, bounded mOTP entry PIN input shared across Android, desktop and native hosts.
 *
 * Note: this is per-entry mOTP (Mobile-OTP) secret material, never a vault-unlock PIN. Input is
 * not saveable, resets when [inputKey] changes, and clears the UI state reference before
 * submission or dismissal. The host validates the live entry and session before persisting.
 */
@Composable
fun SharedEntryPinDialog(
    entryId: Long,
    title: String,
    inputLabel: String,
    confirmLabel: String,
    dismissLabel: String,
    maxLength: Int,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = "",
    error: String? = null,
    inputKey: Any = entryId
) {
    var pin by remember(inputKey) { mutableStateOf(initialValue.take(maxLength)) }
    fun dismiss() { pin = ""; onDismiss() }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= maxLength) pin = it },
                    label = { Text(text = inputLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
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
