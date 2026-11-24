package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds
import com.fairuniversal.offlineotp.ui.theme.ForestType

/** Controlled, non-saveable credential form. Hosts own validation and clear both field values. */
@Composable
fun SharedSetupCredentialsScreen(
    title: String, description: String, choices: List<String>, selected: Int, enabledChoices: Set<Int>,
    passwordRequired: Boolean, password: String, confirmation: String, passwordLabel: String, confirmationLabel: String,
    warning: String, onSelect: (Int) -> Unit, onPassword: (String) -> Unit, onConfirmation: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = title, style = ForestType.title)
        Text(text = description)
        choices.forEachIndexed { index, label ->
            com.fairuniversal.offlineotp.ui.vault.ForestTextAction(
                text = if (index == selected) "Selected: $label" else label, enabled = index in enabledChoices,
                onClick = { onSelect(index) })
        }
        if (passwordRequired) {
            OutlinedTextField(value = password, onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) onPassword(it) },
                label = { Text(passwordLabel) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(value = confirmation, onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) onConfirmation(it) },
                label = { Text(confirmationLabel) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onNext() }))
        }
        if (warning.isNotEmpty()) Text(warning)
    }
}
