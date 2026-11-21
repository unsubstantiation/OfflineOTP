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
import com.fairuniversal.offlineotp.ui.portable.PortableEntryDraft
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds
import com.fairuniversal.offlineotp.ui.theme.ForestPalette

private val PORTABLE_OFFERABLE_KINDS = listOf("TOTP", "HOTP", "STEAM", "MOTP", "NOTE", "SSH_KEY")
private val PORTABLE_OTP_KINDS = listOf("TOTP", "HOTP", "STEAM", "MOTP")
private val PORTABLE_HASH_ALGORITHMS = listOf("SHA1", "SHA256", "SHA512")

/**
 * Native-compatible Compose form for adding/editing OTP, Note, and SSH-key entries.
 *
 * Secrets stay inside composition only and are scrubbed from local state before [onSubmit] or
 * [onDismiss] runs.
 */
@Composable
fun SharedManualEntryDialog(
    inputKey: Any,
    initial: PortableEntryDraft,
    editing: Boolean,
    error: String?,
    onSubmit: (PortableEntryDraft) -> Unit,
    onDismiss: () -> Unit,
    onSwitchToEmail: (() -> Unit)? = null,
    thumbnailNames: List<String> = emptyList(),
    onShowQrCode: (() -> Unit)? = null,
    onShareKeystrokes: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var input by remember(inputKey) { mutableStateOf(initial) }
    var pickingThumbnail by remember(inputKey) { mutableStateOf(false) }
    fun dismiss() {
        pickingThumbnail = false
        input = PortableEntryDraft()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = if (editing) "Edit entry" else "Add entry") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = "Type: ${if (input.kind == "SSH_KEY") "Keys" else input.kind}")
                if (!editing) {
                    for (kind in PORTABLE_OFFERABLE_KINDS) {
                        ForestTextAction(
                            text = if (kind == "SSH_KEY") "Keys" else kind,
                            enabled = input.kind != kind,
                            onClick = {
                                input = PortableEntryDraft(
                                    kind = kind,
                                    issuer = input.issuer,
                                    label = input.label,
                                    tags = input.tags,
                                    digits = if (kind == "STEAM") "5" else "6",
                                    period = "30",
                                    counter = "0",
                                    algorithm = "SHA1"
                                )
                            }
                        )
                    }
                    if (onSwitchToEmail != null) {
                        ForestTextAction(text = "Email account", onClick = {
                            input = PortableEntryDraft()
                            onSwitchToEmail()
                        })
                    }
                } else if (initial.kind in PORTABLE_OTP_KINDS) {
                    for (kind in PORTABLE_OTP_KINDS) {
                        ForestTextAction(
                            text = kind,
                            enabled = input.kind != kind,
                            onClick = {
                                input = input.copy(
                                    kind = kind,
                                    secret = "",
                                    pin = "",
                                    digits = if (kind == "STEAM") "5" else "6",
                                    period = "30",
                                    counter = "0",
                                    algorithm = if (kind == "STEAM") "SHA1" else input.algorithm,
                                    clearPin = false
                                )
                            }
                        )
                    }
                }
                if (editing) Text(text = "Removal choices apply only when you Save.")
                PortableEntryField("Issuer / title", input.issuer, limit = PortableInputBounds.MAX_TEXT_CHARS) {
                    input = input.copy(issuer = it)
                }
                PortableEntryField("Account", input.label, limit = PortableInputBounds.MAX_TEXT_CHARS) {
                    input = input.copy(label = it)
                }
                val note = input.kind == "NOTE"
                val ssh = input.kind == "SSH_KEY"
                if (ssh) {
                    PortableEntryField("Public key", input.publicKey, multiline = true) {
                        input = input.copy(publicKey = it)
                    }
                    PortableEntryField(
                        label = if (editing) "New private key (blank keeps stored)" else "Private key",
                        value = input.privateKey,
                        masked = true,
                        multiline = true
                    ) {
                        input = input.copy(privateKey = it)
                    }
                    PortableEntryField("SSH host (optional)", input.sshHost, limit = PortableInputBounds.MAX_TEXT_CHARS) {
                        input = input.copy(sshHost = it)
                    }
                    PortableEntryField("SSH port (optional)", input.sshPort, limit = 5) {
                        input = input.copy(sshPort = it)
                    }
                    PortableEntryField("SSH user name (optional)", input.sshUsername, limit = PortableInputBounds.MAX_TEXT_CHARS) {
                        input = input.copy(sshUsername = it)
                    }
                    if (!input.clearSshPassword) {
                        PortableEntryField(
                            label = if (editing) "New SSH password (blank keeps stored)" else "SSH password (optional)",
                            value = input.sshPassword,
                            masked = true,
                            limit = PortableInputBounds.MAX_TEXT_CHARS
                        ) {
                            input = input.copy(sshPassword = it)
                        }
                    }
                    if (editing) {
                        ForestTextAction(
                            text = if (input.clearSshPassword) "Will remove SSH password — undo" else "Remove SSH password on Save",
                            onClick = { input = input.copy(sshPassword = "", clearSshPassword = !input.clearSshPassword) }
                        )
                    }
                } else {
                    PortableEntryField(
                        label = if (note) "Note" else "Secret",
                        value = input.secret,
                        masked = !note && !editing,
                        multiline = note,
                        limit = if (note) PortableInputBounds.MAX_NOTE_CHARACTERS else PortableInputBounds.MAX_URI_CHARS
                    ) {
                        input = input.copy(secret = it)
                    }
                }
                if (!ssh) {
                    if (!input.clearHiddenLabel) {
                        PortableEntryField(
                            label = "Password",
                            value = input.hiddenLabel,
                            masked = !editing,
                            limit = PortableInputBounds.MAX_TEXT_CHARS
                        ) {
                            input = input.copy(hiddenLabel = it)
                        }
                    }
                    if (editing) {
                        ForestTextAction(
                            text = if (input.clearHiddenLabel) "Will remove Password — undo" else "Remove Password on Save",
                            onClick = { input = input.copy(hiddenLabel = "", clearHiddenLabel = !input.clearHiddenLabel) }
                        )
                    }
                }
                if (input.kind == "TOTP" || input.kind == "HOTP" || input.kind == "STEAM") {
                    PortableEntryField("Digits", input.digits, limit = 2) {
                        input = input.copy(digits = it)
                    }
                    if (input.kind == "HOTP") {
                        PortableEntryField("Counter", input.counter, limit = 20) {
                            input = input.copy(counter = it)
                        }
                    } else {
                        PortableEntryField("Period (seconds)", input.period, limit = 20) {
                            input = input.copy(period = it)
                        }
                    }
                    if (input.kind == "TOTP" || input.kind == "HOTP") {
                        Text(text = "Algorithm: ${input.algorithm}")
                        for (algorithm in PORTABLE_HASH_ALGORITHMS) {
                            ForestTextAction(
                                text = algorithm,
                                enabled = input.algorithm != algorithm,
                                onClick = { input = input.copy(algorithm = algorithm) }
                            )
                        }
                    }
                }
                if (input.kind == "MOTP") {
                    Text(text = "mOTP uses six digits, ten-second steps and MD5; these are protocol-defined.")
                    if (!input.clearPin) {
                        PortableEntryField(
                            label = "PIN (optional)",
                            value = input.pin,
                            masked = !editing,
                            limit = PortableInputBounds.MAX_TAG_CHARS
                        ) {
                            input = input.copy(pin = it)
                        }
                    }
                    if (editing) {
                        ForestTextAction(
                            text = if (input.clearPin) "Will remove PIN — undo" else "Remove PIN on Save",
                            onClick = { input = input.copy(pin = "", clearPin = !input.clearPin) }
                        )
                    }
                }
                PortableEntryField(
                    label = "Tags (comma or newline separated)",
                    value = input.tags,
                    multiline = true,
                    limit = PortableInputBounds.MAX_TAGS * (PortableInputBounds.MAX_TAG_CHARS + 1)
                ) {
                    input = input.copy(tags = it)
                }
                if (thumbnailNames.isNotEmpty()) {
                    val artworkSummary = input.thumbnail.ifEmpty {
                        if (editing) "Unchanged" else "Automatic (from issuer)"
                    }
                    Text(text = "Artwork: $artworkSummary")
                    ForestTextAction(
                        text = "Choose artwork…",
                        onClick = { pickingThumbnail = true }
                    )
                }
                if (editing) {
                    onShowQrCode?.let { showQr ->
                        ForestTextAction(text = "Show QR code", onClick = showQr)
                    }
                    onShareKeystrokes?.let { shareKeys ->
                        ForestTextAction(text = "Share as keystrokes", onClick = shareKeys)
                    }
                    onDelete?.let { deleteEntry ->
                        ForestTextAction(
                            text = "Delete",
                            color = ForestPalette.warning,
                            onClick = {
                                input = PortableEntryDraft()
                                deleteEntry()
                            }
                        )
                    }
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
    if (pickingThumbnail && thumbnailNames.isNotEmpty()) {
        SharedThumbnailPickerDialog(
            selected = input.thumbnail,
            catalogNames = thumbnailNames,
            unchangedLabel = if (editing) "Keep current artwork" else "Automatic (from issuer)",
            onSelect = { chosen ->
                input = input.copy(thumbnail = chosen)
                pickingThumbnail = false
            },
            onDismiss = { pickingThumbnail = false }
        )
    }
}

@Composable
private fun PortableEntryField(
    label: String,
    value: String,
    masked: Boolean = false,
    multiline: Boolean = false,
    limit: Int = PortableInputBounds.MAX_URI_CHARS,
    change: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= limit) change(it) },
        singleLine = !multiline,
        label = { Text(text = label) },
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None
    )
}
