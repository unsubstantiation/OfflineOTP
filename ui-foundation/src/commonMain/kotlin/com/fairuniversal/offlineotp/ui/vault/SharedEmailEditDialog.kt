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
import com.fairuniversal.offlineotp.ui.portable.PortableEmailEditDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEmailField
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds
import com.fairuniversal.offlineotp.ui.theme.ForestPalette

/**
 * Shared comprehensive multi-identity email editor across Android, Desktop and iOS.
 *
 * Exposes issuer, label, Hidden Label, newline-separated tags, artwork, every stored incoming
 * account field, and every stored SMTP identity field (`#1..#N`), including imported FairEmail
 * extensions. Credentials are never stored in UI restoration state (`rememberSaveable`).
 */
@Composable
fun SharedEmailEditDialog(
    inputKey: Any,
    initial: PortableEmailEditDraft,
    thumbnailNames: List<String>,
    error: String?,
    onSubmit: (PortableEmailEditDraft) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var input by remember(inputKey) { mutableStateOf(initial) }
    val artworkOptions = remember(thumbnailNames, input.thumbnail) {
        (listOf(input.thumbnail) + thumbnailNames)
            .filter { it.isNotEmpty() }
            .distinct()
            .map { it to it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit email entry") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(PortableInputBounds.EMAIL_EDIT_WARNING)
                SharedEmailEditText("Issuer", input.issuer) { input = input.copy(issuer = it) }
                SharedEmailEditText("Account", input.label) { input = input.copy(label = it) }
                SharedEmailEditText("Password", input.hiddenLabel) { input = input.copy(hiddenLabel = it) }
                SharedEmailEditText("Tags (one per line)", input.tags) { input = input.copy(tags = it) }
                if (artworkOptions.isNotEmpty()) {
                    EmailChoiceField("Artwork", input.thumbnail, artworkOptions) {
                        input = input.copy(thumbnail = it)
                    }
                }
                SharedEmailRecordFields("Incoming account", input.account) {
                    input = input.copy(account = it)
                }
                input.identities.forEachIndexed { index, fields ->
                    SharedEmailRecordFields("SMTP identity #${index + 1}", fields) { updated ->
                        input = input.copy(
                            identities = input.identities.mapIndexed { i, old ->
                                if (i == index) updated else old
                            }
                        )
                    }
                }
                if (onDelete != null) {
                    ForestTextAction(
                        text = "Delete",
                        color = ForestPalette.warning,
                        onClick = onDelete
                    )
                }
                error?.let { Text(it) }
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
private fun SharedEmailRecordFields(
    title: String,
    fields: List<PortableEmailField>,
    change: (List<PortableEmailField>) -> Unit
) {
    Text(title)
    fields.forEachIndexed { index, field ->
        fun update(value: String) {
            change(PortableEmailField.updated(fields, index, value))
        }
        when {
            field.name == "encryption" -> EmailChoiceField("Encryption", field.value, EMAIL_ENCRYPTION_OPTIONS, ::update)
            field.name == "protocol" -> EmailChoiceField("Protocol", field.value, EMAIL_PROTOCOL_OPTIONS, ::update)
            field.kind == PortableEmailField.KIND_BOOLEAN -> EmailChoiceField(
                field.name,
                field.value,
                listOf("true" to "Yes", "false" to "No"),
                ::update
            )
            field.name == "password" -> {
                var show by remember { mutableStateOf(false) }
                if (show) {
                    SharedEmailEditText("Password / token", field.value, ::update)
                } else {
                    OutlinedTextField(
                        value = field.value,
                        onValueChange = { if (it.length <= PortableInputBounds.MAX_EMAIL_BYTES) update(it) },
                        label = { Text("Password / token") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                ForestTextAction(
                    if (show) "Hide password" else "Show password",
                    onClick = { show = !show }
                )
            }
            else -> SharedEmailEditText(field.name, field.value, ::update)
        }
    }
}

@Composable
private fun SharedEmailEditText(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= PortableInputBounds.MAX_EMAIL_BYTES) change(it) },
        label = { Text(label) }
    )
}
