package com.fairuniversal.offlineotp.ui.intro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/**
 * Shared Compose form for first-run setup slides.
 *
 * Host adapters own storage, cryptographic key derivation and file pickers. Password fields stay
 * ephemeral in composition memory and are cleared before submission.
 */
@Composable
fun SharedIntroScreen(
    strings: IntroStrings,
    encryptionType: Constants.EncryptionType,
    onEncryptionChanged: (Constants.EncryptionType) -> Unit,
    authMethod: Constants.AuthMethod,
    onAuthMethodChanged: (Constants.AuthMethod) -> Unit,
    onPasswordInput: (String, String) -> Unit,
    onSaveSettings: (Constants.EncryptionType, Constants.AuthMethod, String?, Boolean) -> Unit,
    onRestoreBackup: () -> Unit,
    syncEnabled: Boolean,
    onSyncEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    error: String? = null,
    creating: Boolean = false
) {
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
            var password by remember { mutableStateOf("") }
            var confirmation by remember { mutableStateOf("") }
            val resolvedAuth = if (encryptionType == Constants.EncryptionType.PASSWORD) {
                Constants.AuthMethod.PASSWORD
            } else {
                authMethod
            }
            val requiresPassword = resolvedAuth == Constants.AuthMethod.PASSWORD

            Text(text = strings.appTitle, style = ForestType.title, color = ForestPalette.textPrimary)
            Text(text = strings.encryptionTitle, style = ForestType.title, color = ForestPalette.textPrimary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (option in listOf(
                    Constants.EncryptionType.PASSWORD to "Password",
                    Constants.EncryptionType.KEYSTORE to "KeyStore"
                )) {
                    val selected = encryptionType == option.first
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) ForestPalette.primary else ForestPalette.surfaceVariant,
                            contentColor = ForestPalette.textPrimary
                        ),
                        onClick = {
                            if (option.first != Constants.EncryptionType.PASSWORD && authMethod != Constants.AuthMethod.PASSWORD) {
                                password = ""
                                confirmation = ""
                            }
                            onEncryptionChanged(option.first)
                            if (option.first == Constants.EncryptionType.PASSWORD && authMethod != Constants.AuthMethod.PASSWORD) {
                                onAuthMethodChanged(Constants.AuthMethod.PASSWORD)
                            }
                        }
                    ) {
                        Text(text = option.second, style = ForestType.label)
                    }
                }
            }
            Text(
                text = if (encryptionType == Constants.EncryptionType.PASSWORD) strings.encryptionDescPassword else strings.encryptionDescKeystore,
                style = ForestType.body,
                color = ForestPalette.textSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )

            Text(text = strings.authTitle, style = ForestType.title, color = ForestPalette.textPrimary, modifier = Modifier.padding(top = 16.dp))
            if (encryptionType == Constants.EncryptionType.KEYSTORE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (option in listOf(
                        Constants.AuthMethod.DEVICE to "Device",
                        Constants.AuthMethod.PASSWORD to "Password",
                        Constants.AuthMethod.NONE to "None"
                    )) {
                        val selected = resolvedAuth == option.first
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) ForestPalette.primary else ForestPalette.surfaceVariant,
                                contentColor = ForestPalette.textPrimary
                            ),
                            onClick = {
                                if (option.first != Constants.AuthMethod.PASSWORD) {
                                    password = ""
                                    confirmation = ""
                                }
                                onAuthMethodChanged(option.first)
                            }
                        ) {
                            Text(text = option.second, style = ForestType.label)
                        }
                    }
                }
                if (resolvedAuth == Constants.AuthMethod.NONE && strings.authWarnings.isNotEmpty()) {
                    Text(text = strings.authWarnings, style = ForestType.label, color = ForestPalette.warning)
                }
            }
            Text(
                text = if (encryptionType == Constants.EncryptionType.PASSWORD) strings.authDescPassword else strings.authDescKeystore,
                style = ForestType.body,
                color = ForestPalette.textSecondary
            )

            if (error != null) {
                Text(text = error, style = ForestType.label, color = ForestPalette.warning, modifier = Modifier.padding(top = 8.dp))
            }

            if (requiresPassword) {
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) {
                            password = it
                            onPasswordInput(it, confirmation)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    label = { Text(text = strings.passwordLabel, style = ForestType.label) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = {})
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = {
                        if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) {
                            confirmation = it
                            onPasswordInput(password, it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    label = { Text(text = strings.confirmPasswordLabel, style = ForestType.label) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {})
                )
            }

            Text(text = strings.syncTitle, style = ForestType.title, color = ForestPalette.textPrimary, modifier = Modifier.padding(top = 16.dp))
            Text(text = strings.syncDesc, style = ForestType.body, color = ForestPalette.textSecondary)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Switch(
                    checked = syncEnabled,
                    onCheckedChange = onSyncEnabledChanged,
                    enabled = !busy
                )
                Text(
                    text = if (syncEnabled) strings.syncEnabledText else strings.syncDisabledText,
                    style = ForestType.body,
                    color = ForestPalette.textPrimary
                )
            }

            Text(
                text = if (busy) strings.finishedTitleProgress else (if (error != null) strings.finishedTitleFailed else strings.finishedTitleSuccess),
                style = ForestType.title,
                color = ForestPalette.textPrimary,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = if (busy) strings.finishedDescProgress else (if (error != null) strings.finishedDescFailed else strings.finishedDescSuccess),
                style = ForestType.body,
                color = if (error != null) ForestPalette.warning else ForestPalette.textSecondary
            )

            val canSubmit = !busy && (!requiresPassword || (password.isNotEmpty() && confirmation == password))
            val buttonColors = ButtonDefaults.buttonColors(
                containerColor = ForestPalette.surfaceVariant,
                contentColor = ForestPalette.textPrimary,
                disabledContainerColor = ForestPalette.surfaceVariant,
                disabledContentColor = ForestPalette.textPrimary
            )

            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = buttonColors,
                enabled = canSubmit && !busy,
                onClick = {
                    val submitted = if (requiresPassword) password else null
                    password = ""
                    confirmation = ""
                    onSaveSettings(
                        encryptionType,
                        resolvedAuth,
                        submitted,
                        syncEnabled
                    )
                }
            ) {
                Text(text = strings.buttonCreateVault, style = ForestType.label)
            }

            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = buttonColors,
                enabled = !busy,
                onClick = {
                    password = ""
                    confirmation = ""
                    onRestoreBackup()
                }
            ) {
                Text(text = strings.buttonRestoreBackup, style = ForestType.label)
            }
        }
    }
}

/** Strings provided by the host adapter. */
data class IntroStrings(
    val appTitle: String,
    val encryptionTitle: String,
    val encryptionDescKeystore: String,
    val encryptionDescPassword: String,
    val authTitle: String,
    val authDescPassword: String,
    val authDescKeystore: String,
    val authWarnings: String,
    val syncTitle: String,
    val syncDesc: String,
    val syncEnabledText: String,
    val syncDisabledText: String,
    val finishedTitleProgress: String,
    val finishedDescProgress: String,
    val finishedTitleSuccess: String,
    val finishedDescSuccess: String,
    val finishedTitleFailed: String,
    val finishedDescFailed: String,
    val buttonCreateVault: String,
    val buttonRestoreBackup: String,
    val passwordLabel: String,
    val confirmPasswordLabel: String,
    val unlockAction: String,
    val unlocking: String
)

/** Native capability constants mapped to Compose. */
object Constants {
    enum class EncryptionType { PASSWORD, KEYSTORE }
    enum class AuthMethod { PASSWORD, DEVICE, NONE }
}
