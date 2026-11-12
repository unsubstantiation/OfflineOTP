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
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.Utilities.InputPolicy

@Composable
fun EmailSettingsDialog(inputKey: Any, initial: EmailSettingsInput, failed: Boolean,
                        onSubmit: (EmailSettingsInput) -> Unit, onDismiss: () -> Unit) {
    var input by remember(inputKey) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text = "Edit email connection settings") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(text = "Changes are stored offline; servers are not contacted. Passwords/tokens, authentication types and identities are preserved. Verify ports after changing protocol/encryption; they do not change automatically. None disables transport encryption.")
            Text(text = "Incoming protocol: ${if (input.protocol == 0) "IMAP" else "POP"}")
            ForestTextAction(text = "Use IMAP", onClick = { input = input.copy(protocol = 0) })
            ForestTextAction(text = "Use POP", onClick = { input = input.copy(protocol = 1) })
            ConnectionFields("Incoming", input.incoming) { input = input.copy(incoming = it) }
            input.outgoing.forEachIndexed { index, connection ->
                ConnectionFields("Outgoing SMTP #${index + 1}", connection) { next ->
                    input = input.copy(outgoing = input.outgoing.mapIndexed { i, old -> if (i == index) next else old })
                }
            }
            if (failed) Text(text = "Could not save settings. Check hosts, ports, encryption and storage. Original account retained.")
        } }, confirmButton = { ForestTextAction(text = "Save", onClick = { onSubmit(input) }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) })
}

@Composable
private fun ConnectionFields(label: String, input: EmailConnectionInput, change: (EmailConnectionInput) -> Unit) {
    Text(text = label)
    OutlinedTextField(value = input.host, onValueChange = { if (it.length <= InputPolicy.MAX_TEXT_CHARS) change(input.copy(host = it)) }, label = { Text(text = "$label host") }, singleLine = true)
    OutlinedTextField(value = input.port, onValueChange = { if (it.length <= 5) change(input.copy(port = it)) }, label = { Text(text = "$label port (1–65535)") }, singleLine = true)
    OutlinedTextField(value = input.username, onValueChange = { if (it.length <= EmailMaterial.MAX_CREDENTIAL_CHARS) change(input.copy(username = it)) }, label = { Text(text = "$label username") }, singleLine = true)
    val modes = listOf("SSL", "STARTTLS", "None (unencrypted)")
    Text(text = "$label encryption: ${modes.getOrNull(input.encryption) ?: "Unknown"}")
    modes.forEachIndexed { index, name -> ForestTextAction(text = "$label: $name", onClick = { change(input.copy(encryption = index)) }) }
}
