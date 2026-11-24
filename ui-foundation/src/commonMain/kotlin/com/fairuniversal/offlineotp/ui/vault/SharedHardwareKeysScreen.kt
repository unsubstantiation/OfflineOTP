package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds
import com.fairuniversal.offlineotp.ui.portable.PortableToolGroup
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/** Engine-free hardware management page. Native hosts own discovery, expiry and device authority. */
@Composable
fun SharedHardwareKeysScreen(
    inputKey: Any,
    title: String,
    introduction: String,
    passwordLabel: String,
    groups: List<PortableToolGroup>,
    status: String,
    busy: Boolean,
    onSelectTool: (String, CharArray) -> Unit
) {
    var password by remember(inputKey) { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().background(ForestPalette.backgroundDeep)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = title, style = ForestType.title, color = ForestPalette.textPrimary)
        Text(text = introduction, style = ForestType.body, color = ForestPalette.textSecondary)
        OutlinedTextField(
            value = password,
            onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) password = it },
            enabled = !busy,
            label = { Text(text = passwordLabel) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        if (busy) LinearProgressIndicator()
        SharedVaultToolGroups(groups = groups, message = status, onSelectTool = { key ->
            val submitted = password.toCharArray()
            password = ""
            try { onSelectTool(key, submitted) } finally { submitted.fill('\u0000') }
        })
    }
}
