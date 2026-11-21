package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds
import com.fairuniversal.offlineotp.ui.theme.ForestPalette

/**
 * Native-compatible searchable brand-logo / thumbnail name selector.
 *
 * Host adapters pass their platform [catalogNames] so `:ui-foundation` remains free of `:core`.
 * Passing `""` to [onSelect] restores automatic issuer resolution (create) or the default mark.
 */
@Composable
fun SharedThumbnailPickerDialog(
    title: String = "Choose artwork",
    selected: String = "",
    catalogNames: List<String>,
    unchangedLabel: String = "Automatic (from issuer)",
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()
    val filtered = remember(catalogNames, trimmed) {
        if (trimmed.isEmpty()) catalogNames
        else catalogNames.filter { it.contains(trimmed, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { if (it.length <= PortableInputBounds.MAX_TAG_CHARS) query = it },
                    singleLine = true,
                    label = { Text(text = "Search brand logos") },
                    modifier = Modifier.fillMaxWidth()
                )
                ForestTextAction(text = unchangedLabel, onClick = { onSelect("") })
                LazyColumn(modifier = Modifier.height(240.dp)) {
                    items(filtered, key = { it }) { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(name) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            VaultLetterTile(seed = name, title = name, sizeDp = 28f)
                            Text(
                                text = if (name == selected) "$name (selected)" else name,
                                color = if (name == selected) ForestPalette.accent else ForestPalette.textPrimary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { ForestTextAction(text = "Close", onClick = onDismiss) }
    )
}
