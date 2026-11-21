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
import com.fairuniversal.offlineotp.ui.portable.PortableEmailAccountDraft
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds

/**
 * Shared ephemeral email account form across Desktop, Android and iOS.
 *
 * Uses no saveable state and performs no network account verification; password fields are cleared
 * from local Compose state before invoking [onSubmit] or [onDismiss].
 */
@Composable
fun SharedEmailAccountDialog(
    inputKey: Any,
    error: String?,
    onSubmit: (PortableEmailAccountDraft) -> Unit,
    onDismiss: () -> Unit,
    initial: PortableEmailAccountDraft = PortableEmailAccountDraft(),
    editing: Boolean = false,
    showAuthFields: Boolean = true,
    submitNotice: String? = null,
    maxTextChars: Int = PortableInputBounds.MAX_TEXT_CHARS,
    maxCredentialChars: Int = PortableInputBounds.MAX_EMAIL_CREDENTIAL_CHARS
) {
    var input by remember(inputKey) { mutableStateOf(initial) }
    fun dismiss() {
        input = PortableEmailAccountDraft()
        onDismiss()
    }
    val passHint = if (editing) " (blank keeps stored)" else ""
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = if (editing) "Edit email account" else "Add email account") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                submitNotice?.let { Text(text = it) }
                Text(text = PortableInputBounds.EMAIL_EDIT_WARNING)
                SharedEmailField("Account name (optional)", input.name, limit = maxTextChars) {
                    input = input.copy(name = it)
                }
                SharedEmailField("Email address", input.address, limit = maxTextChars) {
                    input = input.copy(address = it)
                }
                EmailChoiceField("Incoming protocol", input.protocol.toString(), EMAIL_PROTOCOL_OPTIONS) {
                    input = input.copy(protocol = it.toInt())
                }
                SharedEmailField("Incoming host", input.incomingHost, limit = maxTextChars) {
                    input = input.copy(incomingHost = it)
                }
                SharedEmailField("Incoming port (1–65535)", input.incomingPort, limit = 5) {
                    input = input.copy(incomingPort = it)
                }
                SharedEmailEncryption("Incoming", input.incomingEncryption) {
                    input = input.copy(incomingEncryption = it)
                }
                if (showAuthFields) {
                    SharedEmailField("Incoming authentication type (FairEmail numeric ID)", input.incomingAuth, limit = 10) {
                        input = input.copy(incomingAuth = it)
                    }
                }
                SharedEmailField("Incoming username", input.incomingUser, limit = maxCredentialChars) {
                    input = input.copy(incomingUser = it)
                }
                SharedEmailField("Incoming password$passHint", input.incomingPassword, secret = true, limit = maxCredentialChars) {
                    input = input.copy(incomingPassword = it)
                }
                SharedEmailField("Outgoing SMTP host", input.outgoingHost, limit = maxTextChars) {
                    input = input.copy(outgoingHost = it)
                }
                SharedEmailField("Outgoing port (1–65535)", input.outgoingPort, limit = 5) {
                    input = input.copy(outgoingPort = it)
                }
                SharedEmailEncryption("Outgoing", input.outgoingEncryption) {
                    input = input.copy(outgoingEncryption = it)
                }
                if (showAuthFields) {
                    SharedEmailField("Outgoing authentication type (FairEmail numeric ID)", input.outgoingAuth, limit = 10) {
                        input = input.copy(outgoingAuth = it)
                    }
                }
                SharedEmailField("Outgoing username", input.outgoingUser, limit = maxCredentialChars) {
                    input = input.copy(outgoingUser = it)
                }
                SharedEmailField("Outgoing password$passHint", input.outgoingPassword, secret = true, limit = maxCredentialChars) {
                    input = input.copy(outgoingPassword = it)
                }
                SharedEmailField(
                    label = "Tags (one per line)",
                    value = input.tags,
                    limit = PortableInputBounds.MAX_TAGS * (PortableInputBounds.MAX_TAG_CHARS + 1),
                    multiline = true
                ) {
                    input = input.copy(tags = it)
                }
                if (error != null) Text(text = error)
            }
        },
        confirmButton = {
            ForestTextAction(text = "Save", onClick = {
                val submitted = input
                input = input.withoutSecrets()
                onSubmit(submitted)
            })
        },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) }
    )
}

@Composable
private fun SharedEmailEncryption(label: String, selected: Int, change: (Int) -> Unit) {
    EmailChoiceField("$label encryption", selected.toString(), EMAIL_ENCRYPTION_OPTIONS) {
        change(it.toInt())
    }
}

@Composable
private fun SharedEmailField(
    label: String,
    value: String,
    secret: Boolean = false,
    limit: Int = PortableInputBounds.MAX_TEXT_CHARS,
    multiline: Boolean = false,
    change: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= limit) change(it) },
        singleLine = !multiline,
        label = { Text(text = label) },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None
    )
}
