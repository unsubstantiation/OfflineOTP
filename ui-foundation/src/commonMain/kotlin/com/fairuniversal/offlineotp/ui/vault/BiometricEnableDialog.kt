package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp

/** Shared biometric-enrolment password confirmation. The host verifies/stores the vault key. */
@Composable
fun BiometricEnableDialog(
    title: String,
    detail: String,
    passwordLabel: String,
    confirmLabel: String,
    cancelLabel: String,
    initialError: String?,
    onConfirm: (String) -> String?,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(initialError) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(detail, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it; error = null },
                    label = { Text(passwordLabel) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            ForestTextAction(text = confirmLabel, onClick = {
                error = onConfirm(password)
                if (error == null) password = ""
            }, enabled = password.isNotEmpty())
        },
        dismissButton = { ForestTextAction(text = cancelLabel, onClick = onDismiss) }
    )
}
