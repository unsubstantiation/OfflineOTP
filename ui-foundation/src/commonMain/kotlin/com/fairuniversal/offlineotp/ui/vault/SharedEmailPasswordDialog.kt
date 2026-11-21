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
import androidx.compose.ui.text.input.VisualTransformation
import com.fairuniversal.offlineotp.ui.portable.PortableEmailPasswordDraft
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds

/**
 * Shared masked credential-only email password/token editor across Android, Desktop and iOS.
 *
 * Existing passwords are never preloaded; leaving a field blank preserves the stored credential.
 */
@Composable
fun SharedEmailPasswordDialog(
    inputKey: Any,
    initial: PortableEmailPasswordDraft,
    failed: Boolean,
    errorText: String? = null,
    visualTransformation: VisualTransformation = PasswordVisualTransformation(),
    onSubmit: (PortableEmailPasswordDraft) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember(inputKey) { mutableStateOf(initial.withoutSecrets()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change email passwords / tokens") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(PortableInputBounds.EMAIL_EDIT_WARNING)
                Text("Leave a field blank to keep its stored credential.")
                SharedCredentialField("Incoming password / token", input.incoming, visualTransformation) {
                    input = input.copy(incoming = it)
                }
                input.outgoing.forEachIndexed { index, value ->
                    val label = if (index == 0) "Outgoing password / token" else "Outgoing #${index + 1} password / token"
                    SharedCredentialField(label, value, visualTransformation) { next ->
                        input = input.copy(
                            outgoing = input.outgoing.mapIndexed { i, old -> if (i == index) next else old }
                        )
                    }
                }
                val failureMessage = errorText
                    ?: if (failed) "Could not save email credentials. Original entry retained; re-enter changes to retry." else null
                failureMessage?.let { Text(it) }
            }
        },
        confirmButton = {
            ForestTextAction("Save", onClick = {
                val submitted = input
                input = input.withoutSecrets(); onSubmit(submitted)
            })
        },
        dismissButton = {
            ForestTextAction("Cancel", onClick = onDismiss)
        }
    )
}

@Composable
private fun SharedCredentialField(
    label: String,
    value: String,
    visualTransformation: VisualTransformation,
    change: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= PortableInputBounds.MAX_EMAIL_CREDENTIAL_CHARS) change(it) },
        label = { Text(label) },
        visualTransformation = visualTransformation
    )
}
