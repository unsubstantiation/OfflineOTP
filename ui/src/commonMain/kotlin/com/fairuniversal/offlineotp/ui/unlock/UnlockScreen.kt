package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.fairuniversal.offlineotp.ui.vault.ForestTextAction
import com.fairuniversal.offlineotp.ui.vault.VaultStrings

/**
 * Password unlock, shared by every Compose host.
 *
 * The typed password lives in a `remember { mutableStateOf(...) }` and deliberately **not** in
 * `rememberSaveable`: process death must return to a closed vault, never to a filled field. The
 * composable clears the field before handing off an attempt. Failure requires re-entry, rather
 * than retaining the password in UI state during background work. JVM strings cannot be zeroized.
 *
 * Nothing is decided here. [onSubmit] hands the password to the host's own unlock path (Android's
 * `VaultSession`, the desktop's `VaultController`), which owns the rate limiting, key derivation and
 * the resulting session; [error] is whatever that path chose to show.
 */
@Composable
fun UnlockScreen(
    strings: VaultStrings,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    busy: Boolean = false,
    hint: String? = null,
    onHardwareUnlock: (() -> Unit)? = null
) {
    var password by remember { mutableStateOf("") }
    val canSubmit = !busy && password.isNotEmpty()
    Box(modifier = modifier.fillMaxSize().background(ForestPalette.backgroundDeep)) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 380.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = strings.appTitle, style = ForestType.title, color = ForestPalette.textPrimary)
            Text(text = strings.unlockTitle, style = ForestType.body, color = ForestPalette.textSecondary)
            OutlinedTextField(
                value = password,
                enabled = !busy,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(text = strings.passwordLabel, style = ForestType.label) },
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp)
            )
            if (error != null) {
                Text(text = error, style = ForestType.label, color = ForestPalette.warning)
            }
            if (hint != null) {
                Text(text = hint, style = ForestType.label, color = ForestPalette.textHint)
            }
            if (onHardwareUnlock != null) ForestTextAction(text = "Unlock with security key", enabled = !busy,
                onClick = { password = ""; onHardwareUnlock() })
            Spacer(modifier = Modifier.height(4.dp))
            ForestTextAction(
                text = if (busy) strings.unlocking else strings.unlockAction,
                enabled = canSubmit,
                onClick = {
                    val submitted = password
                    password = ""
                    onSubmit(submitted)
                }
            )
        }
    }
}
