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
import com.fairuniversal.offlineotp.Utilities.EmailFieldInput
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog

/** Every stored account/identity field, including imported extensions; no saveable credentials. */
@Composable
fun EmailEditDialog(inputKey: Any, initial: EmailEditInput, error: String?, onSubmit: (EmailEditInput) -> Unit, onDismiss: () -> Unit) {
    var input by remember(inputKey) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit email entry") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(EMAIL_EDIT_WARNING)
            EmailEditText("Issuer", input.issuer) { input = input.copy(issuer = it) }
            EmailEditText("Label", input.label) { input = input.copy(label = it) }
            EmailEditText("Hidden Label", input.hiddenLabel) { input = input.copy(hiddenLabel = it) }
            EmailEditText("Tags (one per line)", input.tags) { input = input.copy(tags = it) }
            EmailChoiceField("Artwork", input.thumbnail, ThumbnailCatalog.NAMES.map { it to it }) { input = input.copy(thumbnail = it) }
            EmailRecordFields("Incoming account", input.account) { input = input.copy(account = it) }
            input.identities.forEachIndexed { index, fields ->
                EmailRecordFields("SMTP identity #${index + 1}", fields) { updated ->
                    input = input.copy(identities = input.identities.mapIndexed { i, old -> if (i == index) updated else old })
                }
            }
            error?.let { Text(it) }
        }
    }, confirmButton = { ForestTextAction("Save", onClick = { onSubmit(input) }) },
        dismissButton = { ForestTextAction("Cancel", onClick = onDismiss) })
}

@Composable
private fun EmailRecordFields(title: String, fields: List<EmailFieldInput>, change: (List<EmailFieldInput>) -> Unit) {
    Text(title)
    fields.forEachIndexed { index, field ->
        fun update(value: String) { change(EmailFieldInput.updated(fields, index, value)) }
        when {
            field.name == "encryption" -> EmailChoiceField("Encryption", field.value, EMAIL_ENCRYPTION_OPTIONS, ::update)
            field.name == "protocol" -> EmailChoiceField("Protocol", field.value, EMAIL_PROTOCOL_OPTIONS, ::update)
            field.kind == EmailFieldInput.Kind.BOOLEAN -> EmailChoiceField(field.name, field.value, listOf("true" to "Yes", "false" to "No"), ::update)
            field.name == "password" -> {
                var show by remember { mutableStateOf(false) }
                if (show) EmailEditText("Password / token", field.value, ::update)
                else OutlinedTextField(value = field.value, onValueChange = ::update, label = { Text("Password / token") }, visualTransformation = PasswordVisualTransformation())
                ForestTextAction(if (show) "Hide password" else "Show password", onClick = { show = !show })
            }
            else -> EmailEditText(field.name, field.value, ::update)
        }
    }
}

@Composable
private fun EmailEditText(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= EmailMaterial.MAX_BYTES) change(it) }, label = { Text(label) })
}
