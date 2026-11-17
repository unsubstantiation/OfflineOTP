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
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.Utilities.InputPolicy

/** Ephemeral local form only. No saveable state or network account verification. */
@Composable
fun EmailEntryDialog(inputKey: Any, error: String?, onSubmit: (EmailEntryInput) -> Unit, onDismiss: () -> Unit, submitNotice: String? = null) {
    var input by remember(inputKey) { mutableStateOf(EmailEntryInput()) }
    fun dismiss() { input = EmailEntryInput(); onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(text = "Add email account") },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            submitNotice?.let { Text(text = it) }
            Text(text = EMAIL_EDIT_WARNING)
            EmailField("Account name (optional)", input.name) { input = input.copy(name = it) }
            EmailField("Email address", input.address) { input = input.copy(address = it) }
            EmailChoiceField("Incoming protocol", input.protocol.toString(), EMAIL_PROTOCOL_OPTIONS) { input = input.copy(protocol = it.toInt()) }
            EmailField("Incoming host", input.incomingHost) { input = input.copy(incomingHost = it) }
            EmailField("Incoming port (1–65535)", input.incomingPort, limit = 5) { input = input.copy(incomingPort = it) }
            EmailEncryption("Incoming", input.incomingEncryption) { input = input.copy(incomingEncryption = it) }
            EmailField("Incoming authentication type (FairEmail numeric ID)", input.incomingAuth, limit = 10) { input = input.copy(incomingAuth = it) }
            EmailField("Incoming username", input.incomingUser, limit = EmailMaterial.MAX_CREDENTIAL_CHARS) { input = input.copy(incomingUser = it) }
            EmailField("Incoming password", input.incomingPassword, secret = true, limit = EmailMaterial.MAX_CREDENTIAL_CHARS) { input = input.copy(incomingPassword = it) }
            EmailField("Outgoing SMTP host", input.outgoingHost) { input = input.copy(outgoingHost = it) }
            EmailField("Outgoing port (1–65535)", input.outgoingPort, limit = 5) { input = input.copy(outgoingPort = it) }
            EmailEncryption("Outgoing", input.outgoingEncryption) { input = input.copy(outgoingEncryption = it) }
            EmailField("Outgoing authentication type (FairEmail numeric ID)", input.outgoingAuth, limit = 10) { input = input.copy(outgoingAuth = it) }
            EmailField("Outgoing username", input.outgoingUser, limit = EmailMaterial.MAX_CREDENTIAL_CHARS) { input = input.copy(outgoingUser = it) }
            EmailField("Outgoing password", input.outgoingPassword, secret = true, limit = EmailMaterial.MAX_CREDENTIAL_CHARS) { input = input.copy(outgoingPassword = it) }
            EmailField("Tags (one per line)", input.tags, limit = InputPolicy.MAX_TAGS * (InputPolicy.MAX_TAG_CHARS + 1), multiline = true) { input = input.copy(tags = it) }
            if (error != null) Text(text = error)
        } }, confirmButton = { ForestTextAction(text = "Save", onClick = {
            val submitted = input; input = input.withoutSecrets(); onSubmit(submitted)
        }) }, dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) })
}

@Composable
private fun EmailEncryption(label: String, selected: Int, change: (Int) -> Unit) {
    EmailChoiceField("$label encryption", selected.toString(), EMAIL_ENCRYPTION_OPTIONS) { change(it.toInt()) }
}

@Composable
private fun EmailField(label: String, value: String, secret: Boolean = false,
                       limit: Int = InputPolicy.MAX_TEXT_CHARS, multiline: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= limit) change(it) },
        singleLine = !multiline, label = { Text(text = label) },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}
