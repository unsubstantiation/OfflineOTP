package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/**
 * Shared Compose notice dialog with a "don't show again" toggle.
 *
 * Host adapters resolve localized strings and persist the boolean preference.
 */
@Composable
fun HideableDialog(
    inputKey: Any,
    title: String,
    message: String,
    dontShowAgainLabel: String,
    confirmLabel: String,
    initialHidden: Boolean = false,
    onHiddenChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var isHidden by remember(inputKey) { mutableStateOf(initialHidden) }

    fun finish() {
        onHiddenChanged(isHidden)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { finish() },
        title = { Text(text = title, style = ForestType.title, color = ForestPalette.textPrimary) },
        text = {
            ScrollableDialogBody {
                Text(text = message, style = ForestType.body, color = ForestPalette.textSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = !isHidden
                            isHidden = next
                            onHiddenChanged(next)
                        }
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = isHidden,
                        onCheckedChange = { next ->
                            isHidden = next
                            onHiddenChanged(next)
                        }
                    )
                    Text(text = dontShowAgainLabel, style = ForestType.body, color = ForestPalette.textPrimary)
                }
            }
        },
        confirmButton = {
            ForestTextAction(text = confirmLabel, onClick = { finish() })
        }
    )
}
