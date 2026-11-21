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

/**
 * Shared HOTP counter editor. Validation stays host-supplied ([isValid]) so the dialog never
 * offers a value the entry model rejects.
 */
@Composable
fun EntryCounterDialog(
    inputKey: Any,
    initialCounter: String,
    title: String,
    inputLabel: String,
    confirmLabel: String,
    dismissLabel: String,
    isValid: (String) -> Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(inputKey) { mutableStateOf(initialCounter) }
    fun dismiss() { value = ""; onDismiss() }
    val valid = isValid(value)
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= 20) value = it },
                    label = { Text(text = inputLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            ForestTextAction(text = confirmLabel, enabled = valid, onClick = {
                val submitted = value
                value = ""
                onSubmit(submitted)
            })
        },
        dismissButton = { ForestTextAction(text = dismissLabel, onClick = { dismiss() }) }
    )
}
