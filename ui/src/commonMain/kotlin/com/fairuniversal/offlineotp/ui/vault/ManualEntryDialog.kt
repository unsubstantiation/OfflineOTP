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
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryDraft
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.Utilities.TokenCalculator

/** Add/edit form. Input stays in composition; the host validates and owns any created entry. */
@Composable
fun ManualEntryDialog(inputKey: Any, error: String?, onSubmit: (ManualEntryInput) -> Unit, onDismiss: () -> Unit,
                      initial: ManualEntryInput = ManualEntryInput(), editing: Boolean = false) {
    var input by remember(inputKey) { mutableStateOf(initial) }
    fun dismiss() { input = ManualEntryInput(); onDismiss() }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = if (editing) "Edit entry" else "Add entry") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = "Type: ${input.type}")
                if (editing) Text(text = "Type is fixed. Leave secret, note, key, PIN, protected-label and password fields blank to keep their current values.")
                if (!editing) for (type in EntryDraft.offerableTypes()) {
                    ForestTextAction(text = type.name, onClick = {
                        // Switching types must not reinterpret or retain another type's secret.
                        input = ManualEntryInput(type = type, issuer = input.issuer, label = input.label,
                            tags = input.tags, timing = if (type == Entry.OTPType.HOTP) "0" else "30")
                    })
                }
                EntryInputField("Issuer", input.issuer, limit = InputPolicy.MAX_TEXT_CHARS) { input = input.copy(issuer = it) }
                EntryInputField("Label", input.label, limit = InputPolicy.MAX_TEXT_CHARS) { input = input.copy(label = it) }
                val note = input.type == Entry.OTPType.NOTE
                val ssh = input.type == Entry.OTPType.SSH_KEY
                EntryInputField(if (note) "Note" else if (ssh) "Private key" else "Secret", input.secret,
                    masked = !note, multiline = note || ssh,
                    limit = if (note) InputPolicy.MAX_NOTE_CHARACTERS else InputPolicy.MAX_URI_CHARS) { input = input.copy(secret = it) }
                if (input.type == Entry.OTPType.TOTP || input.type == Entry.OTPType.HOTP) {
                    EntryInputField("Digits", input.digits, limit = 2) { input = input.copy(digits = it) }
                    EntryInputField(if (input.type == Entry.OTPType.HOTP) "Counter" else "Period (seconds)", input.timing, limit = 20) { input = input.copy(timing = it) }
                    Text(text = "Algorithm: ${input.algorithm}")
                    for (algorithm in TokenCalculator.HashAlgorithm.values()) {
                        ForestTextAction(text = algorithm.name, onClick = { input = input.copy(algorithm = algorithm) })
                    }
                }
                if (!note && !ssh) {
                    EntryInputField("Protected label (optional)", input.hiddenLabel, limit = InputPolicy.MAX_TEXT_CHARS) { input = input.copy(hiddenLabel = it) }
                }
                if (input.type == Entry.OTPType.MOTP) {
                    EntryInputField("PIN (optional)", input.pin, masked = true, limit = InputPolicy.MAX_TAG_CHARS) { input = input.copy(pin = it) }
                }
                if (ssh) {
                    EntryInputField("Public key", input.publicKey, multiline = true) { input = input.copy(publicKey = it) }
                    EntryInputField("Host (optional)", input.sshHost) { input = input.copy(sshHost = it) }
                    EntryInputField("Port (optional)", input.sshPort, limit = 5) { input = input.copy(sshPort = it) }
                    EntryInputField("Username (optional)", input.sshUser) { input = input.copy(sshUser = it) }
                    EntryInputField("Password (optional)", input.sshPassword, masked = true) { input = input.copy(sshPassword = it) }
                }
                EntryInputField("Tags (one per line)", input.tags, multiline = true,
                    limit = InputPolicy.MAX_TAGS * (InputPolicy.MAX_TAG_CHARS + 1)) { input = input.copy(tags = it) }
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
private fun EntryInputField(label: String, value: String, masked: Boolean = false,
                            multiline: Boolean = false, limit: Int = InputPolicy.MAX_URI_CHARS,
                            change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= limit) change(it) },
        singleLine = !multiline, label = { Text(text = label) },
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None)
}
