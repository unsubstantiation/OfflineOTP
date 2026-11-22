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
import com.fairuniversal.offlineotp.ui.portable.PortableEmailField
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds
import com.fairuniversal.offlineotp.ui.portable.PortablePasswordEditDraft
import com.fairuniversal.offlineotp.ui.theme.ForestPalette

/**
 * Shared password / archive entry editor across Android, Desktop and iOS.
 *
 * Never preloads a stored password; clears any typed password from local Compose state before
 * invoking [onSubmit] or [onDismiss].
 */
@Composable
fun SharedPasswordEntryDialog(
    inputKey: Any,
    initial: PortablePasswordEditDraft,
    failed: Boolean,
    onSubmit: (PortablePasswordEditDraft) -> Unit,
    onDismiss: () -> Unit,
    maxFieldChars: Int = PortableInputBounds.MAX_PASSWORD_FIELD_CHARS,
    errorText: String? = null,
    thumbnailNames: List<String> = emptyList(),
    artworkPicker: (@Composable (selected: String, onSelect: (String) -> Unit, onCancel: () -> Unit) -> Unit)? = null,
    onManageTags: (() -> Unit)? = null,
    onViewDetails: (() -> Unit)? = null,
    onExportArchive: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var revealPassword by remember(inputKey) { mutableStateOf(false) }
    var pickingThumbnail by remember(inputKey) { mutableStateOf(false) }
    var input by remember(inputKey) { mutableStateOf(initial) }
    fun dismiss() {
        pickingThumbnail = false
        input = PortablePasswordEditDraft()
        revealPassword = false
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = if (input.archiveMode) "Edit password archive" else "Edit password") },
        text = {
            if (pickingThumbnail && artworkPicker != null) {
                artworkPicker(
                    input.thumbnail,
                    { chosen ->
                        input = input.copy(thumbnail = chosen.ifEmpty { initial.thumbnail })
                        pickingThumbnail = false
                    },
                    { pickingThumbnail = false }
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (input.archiveMode) {
                        Text(text = "Archive size: ${input.archiveSizeBytes} bytes")
                        OutlinedTextField(
                            value = input.issuer,
                            onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) input = input.copy(issuer = it) },
                            label = { Text(text = "Issuer") }
                        )
                        OutlinedTextField(
                            value = input.label,
                            onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) input = input.copy(label = it) },
                            label = { Text(text = "Account") }
                        )
                    } else if (input.customColumnsMode) {
                        Text(text = "Leave password fields blank to keep stored values.")
                        OutlinedTextField(
                            value = input.issuer,
                            onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) input = input.copy(issuer = it) },
                            label = { Text(text = "Issuer") }
                        )
                        OutlinedTextField(
                            value = input.label,
                            onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) input = input.copy(label = it) },
                            label = { Text(text = "Account") }
                        )
                        input.extraFields.forEachIndexed { idx, field ->
                            val isSecret = field.kind == PortableEmailField.KIND_SECRET
                            OutlinedTextField(
                                value = field.value,
                                onValueChange = { next ->
                                    if (next.length <= maxFieldChars) {
                                        input = input.copy(
                                            extraFields = input.extraFields.mapIndexed { i, old ->
                                                if (i == idx) old.copy(value = next) else old
                                            }
                                        )
                                    }
                                },
                                label = {
                                    Text(text = if (isSecret) "${field.name} (blank keeps stored value)" else field.name)
                                },
                                visualTransformation = if (isSecret && !revealPassword) {
                                    PasswordVisualTransformation()
                                } else {
                                    VisualTransformation.None
                                }
                            )
                        }
                        ForestTextAction(
                            text = if (revealPassword) "Hide password" else "Show password",
                            onClick = { revealPassword = !revealPassword }
                        )
                    } else {
                        Text(text = "Leave password blank to keep it. Other stored columns, including notes and GUIDs, are preserved.")
                        OutlinedTextField(
                            value = input.url,
                            onValueChange = { if (it.length <= maxFieldChars) input = input.copy(url = it) },
                            label = { Text(text = "URL") }
                        )
                        OutlinedTextField(
                            value = input.username,
                            onValueChange = { if (it.length <= maxFieldChars) input = input.copy(username = it) },
                            label = { Text(text = "Username") }
                        )
                        OutlinedTextField(
                            value = input.password,
                            onValueChange = { if (it.length <= maxFieldChars) input = input.copy(password = it) },
                            label = { Text(text = "Password (blank keeps stored value)") },
                            visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation()
                        )
                        ForestTextAction(
                            text = if (revealPassword) "Hide password" else "Show password",
                            onClick = { revealPassword = !revealPassword }
                        )
                        input.extraFields.forEachIndexed { idx, field ->
                            val maskedExtra = field.name.trim().equals("otpauth", ignoreCase = true) && !revealPassword
                            OutlinedTextField(
                                value = field.value,
                                onValueChange = { next ->
                                    if (next.length <= maxFieldChars) {
                                        input = input.copy(
                                            extraFields = input.extraFields.mapIndexed { i, old ->
                                                if (i == idx) old.copy(value = next) else old
                                            }
                                        )
                                    }
                                },
                                label = { Text(text = field.name) },
                                visualTransformation = if (maskedExtra) PasswordVisualTransformation() else VisualTransformation.None
                            )
                        }
                    }
                    OutlinedTextField(
                        value = input.tags,
                        onValueChange = { next ->
                            if (next.length <= PortableInputBounds.MAX_TAGS * (PortableInputBounds.MAX_TAG_CHARS + 1)) {
                                input = input.copy(tags = next)
                            }
                        },
                        singleLine = false,
                        label = { Text(text = "Tags (one per line)") }
                    )
                    if (thumbnailNames.isNotEmpty() || artworkPicker != null) {
                        val artworkSummary = input.thumbnail.ifEmpty { "Keep current" }
                        Text(text = "Artwork: $artworkSummary")
                        ForestTextAction(
                            text = "Choose artwork",
                            onClick = { pickingThumbnail = true }
                        )
                    }
                    if (onManageTags != null) {
                        ForestTextAction(
                            text = "Manage tags",
                            onClick = {
                                input = PortablePasswordEditDraft()
                                revealPassword = false
                                onManageTags()
                            }
                        )
                    }
                    if (!input.archiveMode && onViewDetails != null) {
                        ForestTextAction(
                            text = "View details",
                            onClick = {
                                input = PortablePasswordEditDraft()
                                revealPassword = false
                                onViewDetails()
                            }
                        )
                    }
                    if (input.archiveMode && onExportArchive != null) {
                        ForestTextAction(
                            text = "Export archive",
                            onClick = {
                                input = PortablePasswordEditDraft()
                                revealPassword = false
                                onExportArchive()
                            }
                        )
                    }
                    if (onDelete != null) {
                        ForestTextAction(
                            text = "Delete",
                            color = ForestPalette.warning,
                            onClick = {
                                input = PortablePasswordEditDraft()
                                revealPassword = false
                                onDelete()
                            }
                        )
                    }
                    if (errorText != null) {
                        Text(text = errorText)
                    } else if (failed) {
                        Text(text = "Could not update the password entry. Check the fields and vault storage. Re-enter any new password to retry.")
                    }
                }
            }
        },
        confirmButton = {
            ForestTextAction(text = "Save", enabled = !pickingThumbnail, onClick = {
                val submitted = input
                input = input.withoutPassword()
                revealPassword = false
                onSubmit(submitted)
            })
        },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) }
    )
    if (pickingThumbnail && artworkPicker == null && thumbnailNames.isNotEmpty()) {
        SharedThumbnailPickerDialog(
            selected = input.thumbnail,
            catalogNames = thumbnailNames,
            unchangedLabel = "Keep current artwork",
            onSelect = { chosen ->
                input = input.copy(thumbnail = chosen.ifEmpty { initial.thumbnail })
                pickingThumbnail = false
            },
            onDismiss = { pickingThumbnail = false }
        )
    }
}
