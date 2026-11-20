package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared Compose form for a dialog with a "don't show again" checkbox.
 *
 * Replaces the native Android HideableDialog (50 lines) with a shared Compose form.
 * The host adapter (HideableDialogHost) will wrap this form and handle native capability adapters
 * (Settings wiring, lifecycle, etc.).
 */
@Composable
fun HideableDialog(
    inputKey: Any,
    titleId: Int,
    msgId: Int,
    hideSettingId: Int,
    settingsProvider: (Int) -> Boolean,
    settingsSaver: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var isHidden by remember(inputKey) { mutableStateOf(settingsProvider(hideSettingId)) }
    var showDialog by remember(inputKey) { mutableStateOf(!isHidden) }

    fun dismiss() {
        settingsSaver(hideSettingId, isHidden)
        showDialog = false
        onDismiss()
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                dismiss()
            },
            title = { Text(text = "Title: $titleId") }, // In real app, use string resource lookup
            text = {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(text = "Message: $msgId") // In real app, use string resource lookup
                    Checkbox(
                        checked = isHidden,
                        onCheckedChange = { newChecked: Boolean ->
                            isHidden = newChecked
                            settingsSaver(hideSettingId, newChecked)
                        }
                    ) {
                        Text(text = "Don't show again")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    settingsSaver(hideSettingId, isHidden)
                    dismiss()
                }) {
                    Text(text = "OK")
                }
            }
        )
    }
}
