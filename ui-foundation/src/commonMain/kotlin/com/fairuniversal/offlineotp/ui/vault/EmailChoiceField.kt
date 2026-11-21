package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** One compact selector shared by email creation and editing. Unknown imported values stay visible. */
@Composable
fun EmailChoiceField(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: ${options.firstOrNull { it.first == value }?.second ?: "Unknown ($value)"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChange(key) })
            }
        }
    }
}
val EMAIL_PROTOCOL_OPTIONS = listOf("0" to "IMAP", "1" to "POP3")
val EMAIL_ENCRYPTION_OPTIONS = listOf("0" to "SSL", "1" to "STARTTLS", "2" to "None (unencrypted)")
