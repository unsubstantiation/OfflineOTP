package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.fairuniversal.offlineotp.ui.portable.PortableEmailConnectionDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEmailSettingsDraft
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds

/**
 * Shared non-secret email connection & sender identity settings editor across Android, Desktop and iOS.
 *
 * Supports editing account display name, incoming protocol/server/port/username/authentication/encryption,
 * per-identity sender display name/email/server/port/username/authentication/encryption, and staged
 * removal of non-primary SMTP identities (`#2..#N`) with undo before saving.
 */
@Composable
fun SharedEmailSettingsDialog(
    inputKey: Any,
    initial: PortableEmailSettingsDraft,
    failed: Boolean,
    errorText: String? = null,
    onSubmit: (PortableEmailSettingsDraft) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember(inputKey) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit email settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(PortableInputBounds.EMAIL_EDIT_WARNING)
                input.accountName?.let { name ->
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) input = input.copy(accountName = it) },
                        singleLine = true,
                        label = { Text("Account display name") }
                    )
                }
                Text("Protocol")
                Row {
                    listOf(0 to "IMAP", 1 to "POP").forEach { (value, label) ->
                        ForestTextAction(
                            if (input.protocol == value) "[$label]" else label,
                            onClick = { input = input.copy(protocol = value) }
                        )
                    }
                }
                SharedConnectionFields("Incoming", input.incoming) {
                    input = input.copy(incoming = it)
                }
                input.outgoing.forEachIndexed { index, connection ->
                    val title = if (index == 0) "Outgoing" else "Outgoing #${index + 1}"
                    val removed = index in input.removedIdentities
                    if (removed) {
                        Text("$title marked for removal; Save deletes this identity and its credential.")
                        ForestTextAction("Undo removal", onClick = {
                            input = input.copy(removedIdentities = input.removedIdentities - index)
                        })
                    } else {
                        if (index in input.removableIdentities) {
                            ForestTextAction("Remove SMTP #${index + 1} on Save", onClick = {
                                input = input.copy(removedIdentities = input.removedIdentities + index)
                            })
                        }
                        input.identities?.getOrNull(index)?.let { identity ->
                            OutlinedTextField(
                                value = identity.name,
                                onValueChange = { next ->
                                    if (next.length <= PortableInputBounds.MAX_TEXT_CHARS) {
                                        input = input.copy(
                                            identities = input.identities?.mapIndexed { i, old ->
                                                if (i == index) old.copy(name = next) else old
                                            }
                                        )
                                    }
                                },
                                singleLine = true,
                                label = { Text("$title sender display name") }
                            )
                            OutlinedTextField(
                                value = identity.address,
                                onValueChange = { next ->
                                    if (next.length <= PortableInputBounds.MAX_TEXT_CHARS) {
                                        input = input.copy(
                                            identities = input.identities?.mapIndexed { i, old ->
                                                if (i == index) old.copy(address = next) else old
                                            }
                                        )
                                    }
                                },
                                singleLine = true,
                                label = { Text("$title sender email address") }
                            )
                        }
                        SharedConnectionFields(title, connection) { next ->
                            input = input.copy(
                                outgoing = input.outgoing.mapIndexed { i, old ->
                                    if (i == index) next else old
                                }
                            )
                        }
                    }
                }
                val failureMessage = errorText
                    ?: if (failed) "Could not save email settings. Check server, port and storage; original retained." else null
                failureMessage?.let { Text(it) }
            }
        },
        confirmButton = {
            ForestTextAction("Save", onClick = { onSubmit(input) })
        },
        dismissButton = {
            ForestTextAction("Cancel", onClick = onDismiss)
        }
    )
}

@Composable
private fun SharedConnectionFields(
    title: String,
    value: PortableEmailConnectionDraft,
    change: (PortableEmailConnectionDraft) -> Unit
) {
    Text(title)
    OutlinedTextField(
        value = value.host,
        onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) change(value.copy(host = it)) },
        singleLine = true,
        label = { Text("$title server") }
    )
    OutlinedTextField(
        value = value.port,
        onValueChange = { change(value.copy(port = it.filter(Char::isDigit).take(5))) },
        singleLine = true,
        label = { Text("$title port") }
    )
    OutlinedTextField(
        value = value.username,
        onValueChange = { if (it.length <= PortableInputBounds.MAX_EMAIL_CREDENTIAL_CHARS) change(value.copy(username = it)) },
        singleLine = true,
        label = { Text("$title username") }
    )
    value.authentication?.let { auth ->
        OutlinedTextField(
            value = auth,
            onValueChange = { change(value.copy(authentication = it.filter(Char::isDigit).take(10))) },
            singleLine = true,
            label = { Text("$title authentication type ID") }
        )
    }
    Row {
        listOf(0 to "SSL", 1 to "STARTTLS", 2 to "None").forEach { (mode, label) ->
            ForestTextAction(
                if (value.encryption == mode) "[$label]" else label,
                onClick = { change(value.copy(encryption = mode)) }
            )
        }
    }
}
