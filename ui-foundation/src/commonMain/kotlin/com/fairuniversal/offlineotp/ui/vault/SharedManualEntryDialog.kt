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
 * Shared Compose form for adding/editing OTP, Note, and SSH-key entries across Android, Desktop and iOS.
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
    onSwitchToPassword: (() -> Unit)? = null,
    onSwitchToEmail: (() -> Unit)? = null,
    onSwitchToUriImport: (() -> Unit)? = null,
    submitNotice: String? = null,
    thumbnailNames: List<String> = emptyList(),
    artwork: VaultArtwork? = null,
    artworkPicker: (@Composable (selected: String, onSelect: (String) -> Unit, onCancel: () -> Unit) -> Unit)? = null,
    onShowQrCode: (() -> Unit)? = null,
    onShareKeystrokes: (() -> Unit)? = null,
    onManageTags: (() -> Unit)? = null,
    onViewDetails: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var input by remember(inputKey) { mutableStateOf(initial) }
    var pickingThumbnail by remember(inputKey) { mutableStateOf(false) }
    val canChooseArtwork = artworkPicker != null || thumbnailNames.isNotEmpty()
    val unchangedArtworkLabel = if (editing) "Keep current artwork" else "Automatic from issuer"
    fun dismiss() {
        pickingThumbnail = false
        input = PortableEntryDraft()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = if (editing) "Edit entry" else "Add entry") },
        text = {
            if (pickingThumbnail && artworkPicker != null) {
                artworkPicker(
                    input.thumbnail,
                    { chosen -> input = input.copy(thumbnail = chosen); pickingThumbnail = false },
                    { pickingThumbnail = false }
                )
            } else Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                submitNotice?.let { Text(text = it) }
                Text(text = "Type: ${if (input.kind == "SSH_KEY") "Keys" else input.kind}")
                if (editing) {
                    Text(
                        text = "Stored note and OTP fields are shown for editing. Changing OTP type clears the secret and timing; re-enter them for the new type. Leave fields blank to keep stored values. Removal choices apply only when you Save."
                    )
                }
                if (!editing) {
                    for (type in PORTABLE_OFFERABLE_KINDS) {
                        ForestTextAction(
                            text = if (type == "SSH_KEY") "Keys" else type,
                            enabled = input.kind != type,
                            onClick = {
                                input = PortableEntryDraft(
                                    kind = type,
                                    issuer = input.issuer,
                                    label = input.label,
                                    tags = input.tags,
                                    thumbnail = input.thumbnail,
                                    digits = if (type == "STEAM") "5" else "6",
                                    period = "30",
                                    counter = "0",
                                    algorithm = "SHA1"
                                )
                            }
                        )
                    }
                    if (onSwitchToPassword != null) {
                        ForestTextAction(text = "Website password", onClick = {
                            input = PortableEntryDraft()
                            onSwitchToPassword()
                        })
                    }
                    if (onSwitchToEmail != null) {
                        ForestTextAction(text = "Email account", onClick = {
                            input = PortableEntryDraft()
                            onSwitchToEmail()
                        })
                    }
                    if (onSwitchToUriImport != null) {
                        ForestTextAction(text = "Import otpauth:// link", onClick = {
                            input = PortableEntryDraft()
                            onSwitchToUriImport()
                        })
                    }
                } else if (initial.kind in PORTABLE_OTP_KINDS) {
                    for (type in PORTABLE_OTP_KINDS) {
                        ForestTextAction(
                            text = type,
                            enabled = input.kind != type,
                            onClick = {
                                input = input.copy(
                                    kind = type,
                                    secret = "",
                                    pin = "",
                                    digits = if (type == "STEAM") "5" else "6",
                                    period = "30",
                                    counter = "0",
                                    algorithm = "SHA1",
                                    clearPin = false
                                )
                            }
                        )
                    }
                }
                EntryInputField("Issuer", input.issuer, limit = PortableInputBounds.MAX_TEXT_CHARS) {
                    input = input.copy(issuer = it)
                }
                EntryInputField("Account", input.label, limit = PortableInputBounds.MAX_TEXT_CHARS) {
                    input = input.copy(label = it)
                }
                if (canChooseArtwork) {
                    val artworkSummary = input.thumbnail.ifEmpty {
                        if (editing) "Keep current" else "Automatic from issuer"
                    }
                    Text(text = "Artwork: $artworkSummary")
                    ForestTextAction(text = "Choose artwork", onClick = { pickingThumbnail = true })
                }
                val note = input.kind == "NOTE"
                val ssh = input.kind == "SSH_KEY"
                EntryInputField(
                    label = if (note) "Hidden Note" else if (ssh) {
                        if (editing) "New private key (blank keeps stored)" else "Private key"
                    } else "Secret",
                    value = if (ssh) input.privateKey else input.secret,
                    masked = !note && (!editing || ssh),
                    multiline = note || ssh,
                    limit = if (note) PortableInputBounds.MAX_NOTE_CHARACTERS else PortableInputBounds.MAX_URI_CHARS
                ) { next ->
                    input = if (ssh) input.copy(privateKey = next) else input.copy(secret = next)
                }
                if (input.kind == "TOTP" || input.kind == "HOTP" || (editing && input.kind == "STEAM")) {
                    EntryInputField("Digits", input.digits, limit = 2) {
                        input = input.copy(digits = it)
                    }
                    if (input.kind == "HOTP") {
                        EntryInputField("Counter", input.counter, limit = 20) {
                            input = input.copy(counter = it)
                        }
                    } else {
                        EntryInputField("Period (seconds)", input.period, limit = 20) {
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
                }
                if (!note && !ssh) {
                    if (editing) {
                        ForestTextAction(
                            text = if (input.clearHiddenLabel) "Will remove Password — undo" else "Remove stored Password",
                            onClick = { input = input.copy(hiddenLabel = "", clearHiddenLabel = !input.clearHiddenLabel) }
                        )
                    }
                    if (!input.clearHiddenLabel) EntryInputField(
                        "Password", input.hiddenLabel, masked = !editing, limit = PortableInputBounds.MAX_TEXT_CHARS
                    ) {
                        input = input.copy(hiddenLabel = it)
                    }
                }
                if (input.kind == "MOTP") {
                    if (editing) {
                        ForestTextAction(
                            text = if (input.clearPin) "Will remove PIN — undo" else "Remove stored PIN",
                            onClick = { input = input.copy(pin = "", clearPin = !input.clearPin) }
                        )
                    }
                    if (!input.clearPin) EntryInputField(
                        "PIN (optional)", input.pin, masked = !editing, limit = PortableInputBounds.MAX_TAG_CHARS
                    ) {
                        input = input.copy(pin = it)
                    }
                }
                if (ssh) {
                    EntryInputField("Public key", input.publicKey, multiline = true) {
                        input = input.copy(publicKey = it)
                    }
                    EntryInputField("Host (optional)", input.sshHost, limit = PortableInputBounds.MAX_TEXT_CHARS) {
                        input = input.copy(sshHost = it)
                    }
                    EntryInputField("Port (optional)", input.sshPort, limit = 5) {
                        input = input.copy(sshPort = it)
                    }
                    EntryInputField("Username (optional)", input.sshUsername, limit = PortableInputBounds.MAX_TEXT_CHARS) {
                        input = input.copy(sshUsername = it)
                    }
                    if (editing) {
                        ForestTextAction(
                            text = if (input.clearSshPassword) "Will remove SSH password — undo" else "Remove stored SSH password",
                            onClick = { input = input.copy(sshPassword = "", clearSshPassword = !input.clearSshPassword) }
                        )
                    }
                    if (!input.clearSshPassword) EntryInputField(
                        if (editing) "New SSH password (blank keeps stored)" else "Password (optional)",
                        input.sshPassword, masked = true, limit = PortableInputBounds.MAX_TEXT_CHARS
                    ) {
                        input = input.copy(sshPassword = it)
                    }
                }
                EntryInputField(
                    "Tags (one per line)",
                    input.tags,
                    multiline = true,
                    limit = PortableInputBounds.MAX_TAGS * (PortableInputBounds.MAX_TAG_CHARS + 1)
                ) {
                    input = input.copy(tags = it)
                }
                if (editing) {
                    onManageTags?.let { manageTags ->
                        ForestTextAction(
                            text = "Manage tags",
                            onClick = {
                                input = PortableEntryDraft()
                                manageTags()
                            }
                        )
                    }
                    onShowQrCode?.let { showQr ->
                        ForestTextAction(text = "Show QR code", onClick = showQr)
                    }
                    onShareKeystrokes?.let { shareKeys ->
                        ForestTextAction(text = "Share as keystrokes", onClick = shareKeys)
                    }
                    onViewDetails?.let { viewDetails ->
                        ForestTextAction(text = "View details", onClick = viewDetails)
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
                SharedStatusMessage(error)
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
    if (pickingThumbnail && artworkPicker == null && thumbnailNames.isNotEmpty()) {
        SharedThumbnailPickerDialog(
            selected = input.thumbnail,
            catalogNames = thumbnailNames,
            unchangedLabel = unchangedArtworkLabel,
            artwork = artwork,
            onSelect = { chosen ->
                input = input.copy(thumbnail = if (editing && chosen.isEmpty()) initial.thumbnail else chosen)
                pickingThumbnail = false
            },
            onDismiss = { pickingThumbnail = false }
        )
    }
}

@Composable
private fun EntryInputField(
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
