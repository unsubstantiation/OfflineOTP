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
 * Shared searchable brand-logo / thumbnail picker body across Android, Desktop and iOS.
 *
 * Host adapters pass their platform [catalogNames] and an optional [iconContent] renderer so
 * `:ui-foundation` remains free of `:core` and platform bitmap toolkits. Passing `""` to [onSelect]
 * restores automatic issuer resolution (create) or keeps/resets the default mark.
 */
@Composable
fun SharedThumbnailPickerContent(
    selected: String = "",
    catalogNames: List<String>,
    unchangedLabel: String = "Automatic (from issuer)",
    onSelect: (String) -> Unit,
    onCancel: (() -> Unit)? = null,
    artwork: VaultArtwork? = null,
    iconContent: (@Composable (String) -> Unit)? = null
) {
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()
    val filtered = remember(catalogNames, trimmed) {
        if (trimmed.isEmpty()) catalogNames
        else catalogNames.filter { it.contains(trimmed, ignoreCase = true) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { if (it.length <= PortableInputBounds.MAX_TAG_CHARS) query = it },
            singleLine = true,
            label = { Text(text = "Search artwork") },
            modifier = Modifier.fillMaxWidth()
        )
        ForestTextAction(text = unchangedLabel, onClick = { onSelect("") })
        Text(text = "Default uses a letter tile.")
        if (filtered.isEmpty()) {
            Text(text = "No matching artwork")
        }
        // Explicit height avoids nesting an unbounded scrollable inside AlertDialog content.
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
                    if (iconContent != null) {
                        iconContent(name)
                    } else {
                        SharedCatalogArtworkIcon(name = name, artwork = artwork)
                    }
                    Text(
                        text = if (name == selected) "$name (selected)" else name,
                        color = if (name == selected) ForestPalette.accent else ForestPalette.textPrimary
                    )
                }
            }
        }
        onCancel?.let { cancel ->
            ForestTextAction(text = "Back without changing artwork", onClick = cancel)
        }
    }
}

/** Standalone dialog wrapper around [SharedThumbnailPickerContent] across Android, Desktop and iOS. */
@Composable
fun SharedThumbnailPickerDialog(
    title: String = "Choose artwork",
    selected: String = "",
    catalogNames: List<String>,
    unchangedLabel: String = "Automatic (from issuer)",
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Close",
    artwork: VaultArtwork? = null,
    iconContent: (@Composable (String) -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            SharedThumbnailPickerContent(
                selected = selected,
                catalogNames = catalogNames,
                unchangedLabel = unchangedLabel,
                onSelect = onSelect,
                artwork = artwork,
                iconContent = iconContent
            )
        },
        confirmButton = { ForestTextAction(text = dismissLabel, onClick = onDismiss) }
    )
}
