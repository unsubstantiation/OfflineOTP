package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/**
 * Password unlock, shared by every Compose host.
 *
 * The typed password lives in a `remember { mutableStateOf(...) }` and deliberately **not** in
 * `rememberSaveable`: process death must return to a closed vault, never to a filled field. The
 * composable clears the field before handing off an attempt. Failure requires re-entry, rather
 * than retaining the password in UI state during background work. Immutable strings cannot be reliably zeroized.
 *
 * [onSubmit] hands the password to a host adapter. Authentication, rate limiting, key derivation
 * and session authority remain outside this module; [error] is supplied by that adapter.
 */
@Composable
fun SharedUnlockScreen(
    strings: UnlockStrings,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    busy: Boolean = false,
    hint: String? = null,
    onHardwareUnlock: (() -> Unit)? = null,
    quickUnlockLabel: String = "Unlock with security key",
    creating: Boolean = false,
    onCreate: (String, String) -> Unit = { _, _ -> }
) {
    var password by remember(creating) { mutableStateOf("") }
    var confirmation by remember(creating) { mutableStateOf("") }
    val canSubmit = !busy && password.isNotEmpty() && (!creating || confirmation == password)
    val submitPassword = {
        if (!busy && password.isNotEmpty() && (!creating || confirmation == password)) {
            val submitted = password
            val confirmed = confirmation
            password = ""
            confirmation = ""
            if (creating) onCreate(submitted, confirmed) else onSubmit(submitted)
        }
    }
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = ForestPalette.surfaceVariant,
        contentColor = ForestPalette.textPrimary,
        disabledContainerColor = ForestPalette.surfaceVariant,
        disabledContentColor = ForestPalette.textPrimary
    )
    Box(modifier = modifier.fillMaxSize().background(ForestPalette.backgroundDeep)) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = strings.appTitle, style = ForestType.title, color = ForestPalette.textPrimary)
            OutlinedTextField(
                value = password,
                enabled = !busy,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                        if (event.type == KeyEventType.KeyUp) submitPassword()
                        true
                    } else false
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                singleLine = true,
                label = { Text(text = strings.passwordLabel, style = ForestType.label) },
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp)
            )
            if (creating) {
                Text(text = "Choose a password for a new vault. It cannot be recovered if you forget it.",
                    style = ForestType.body, color = ForestPalette.textSecondary)
                OutlinedTextField(value = confirmation, onValueChange = { confirmation = it },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(text = "Confirm password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitPassword() }))
            }
            if (error != null) {
                Text(text = error, style = ForestType.label, color = ForestPalette.warning)
            }
            if (hint != null) {
                Text(text = hint, style = ForestType.label, color = ForestPalette.textHint)
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                colors = buttonColors,
                enabled = canSubmit,
                onClick = submitPassword
            ) {
                Text(text = if (busy) strings.unlocking else if (creating) "Create vault" else strings.unlockAction)
            }
            if (!creating && onHardwareUnlock != null) Button(
                modifier = Modifier.fillMaxWidth(),
                colors = buttonColors,
                enabled = !busy,
                onClick = { password = ""; confirmation = ""; onHardwareUnlock() }
            ) {
                Text(text = quickUnlockLabel)
            }
        }
    }
}
