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
                      initial: ManualEntryInput = ManualEntryInput(), editing: Boolean = false, artwork: VaultArtwork? = null) {
    var input by remember(inputKey) { mutableStateOf(initial) }
    var pickingThumbnail by remember(inputKey) { mutableStateOf(false) }
    fun dismiss() { input = ManualEntryInput(); onDismiss() }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = if (editing) "Edit entry" else "Add entry") },
        text = {
            if (pickingThumbnail) {
                ThumbnailPicker(selected = input.thumbnailName, artwork = artwork,
                    unchangedLabel = if (editing) "Keep current artwork" else "Automatic from issuer",
                    onSelect = { input = input.copy(thumbnailName = it); pickingThumbnail = false },
                    onCancel = { pickingThumbnail = false })
            } else Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = "Type: ${input.type}")
                if (editing) Text(text = "Type is fixed. Leave secret, note, key, PIN, protected-label and password fields blank to keep their current values. Removal choices apply only when you Save.")
                if (!editing) for (type in EntryDraft.offerableTypes()) {
                    ForestTextAction(text = type.name, onClick = {
                        // Switching types must not reinterpret or retain another type's secret.
                        input = ManualEntryInput(type = type, issuer = input.issuer, label = input.label,
                            tags = input.tags, thumbnailName = input.thumbnailName, timing = if (type == Entry.OTPType.HOTP) "0" else "30")
                    })
                }
                EntryInputField("Issuer", input.issuer, limit = InputPolicy.MAX_TEXT_CHARS) { input = input.copy(issuer = it) }
                EntryInputField("Label", input.label, limit = InputPolicy.MAX_TEXT_CHARS) { input = input.copy(label = it) }
                Text(text = "Artwork: ${input.thumbnailName ?: if (editing) "Keep current" else "Automatic from issuer"}")
                ForestTextAction(text = "Choose artwork", onClick = { pickingThumbnail = true })
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
                    if (editing) ForestTextAction(
                        text = if (input.clearHiddenLabel) "Will remove protected label — undo" else "Remove stored protected label",
                        onClick = { input = input.copy(hiddenLabel = "", clearHiddenLabel = !input.clearHiddenLabel) })
                    if (!input.clearHiddenLabel) EntryInputField("Protected label (optional)", input.hiddenLabel, limit = InputPolicy.MAX_TEXT_CHARS) { input = input.copy(hiddenLabel = it) }
                }
                if (input.type == Entry.OTPType.MOTP) {
                    if (editing) ForestTextAction(
                        text = if (input.clearPin) "Will remove PIN — undo" else "Remove stored PIN",
                        onClick = { input = input.copy(pin = "", clearPin = !input.clearPin) })
                    if (!input.clearPin) EntryInputField("PIN (optional)", input.pin, masked = true, limit = InputPolicy.MAX_TAG_CHARS) { input = input.copy(pin = it) }
                }
                if (ssh) {
                    EntryInputField("Public key", input.publicKey, multiline = true) { input = input.copy(publicKey = it) }
                    EntryInputField("Host (optional)", input.sshHost) { input = input.copy(sshHost = it) }
                    EntryInputField("Port (optional)", input.sshPort, limit = 5) { input = input.copy(sshPort = it) }
                    EntryInputField("Username (optional)", input.sshUser) { input = input.copy(sshUser = it) }
                    if (editing) ForestTextAction(
                        text = if (input.clearSshPassword) "Will remove SSH password — undo" else "Remove stored SSH password",
                        onClick = { input = input.copy(sshPassword = "", clearSshPassword = !input.clearSshPassword) })
                    if (!input.clearSshPassword) EntryInputField("Password (optional)", input.sshPassword, masked = true) { input = input.copy(sshPassword = it) }
                }
                EntryInputField("Tags (one per line)", input.tags, multiline = true,
                    limit = InputPolicy.MAX_TAGS * (InputPolicy.MAX_TAG_CHARS + 1)) { input = input.copy(tags = it) }
                if (error != null) Text(text = error)
            }
        },
        confirmButton = {
            ForestTextAction(text = "Save", enabled = !pickingThumbnail, onClick = {
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
