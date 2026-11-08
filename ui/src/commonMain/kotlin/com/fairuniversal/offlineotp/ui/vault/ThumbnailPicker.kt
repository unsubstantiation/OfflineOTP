package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog
import com.fairuniversal.offlineotp.ui.theme.ForestPalette

/** Catalog-only choices, loaded lazily through the same host artwork provider as vault cards. */
@Composable
internal fun ThumbnailPicker(selected: String?, artwork: VaultArtwork?, unchangedLabel: String,
                             onSelect: (String?) -> Unit, onCancel: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val names = remember(query) { ThumbnailCatalog.NAMES.filter { it.contains(query.trim(), ignoreCase = true) } }
    Column {
        OutlinedTextField(value = query, onValueChange = { if (it.length <= 128) query = it },
            singleLine = true, label = { Text(text = "Search artwork") })
        ForestTextAction(text = unchangedLabel, onClick = { onSelect(null) })
        Text(text = "Default uses a letter tile.")
        if (names.isEmpty()) Text(text = "No matching artwork")
        // Explicit height avoids nesting an unbounded scrollable in AlertDialog content.
        LazyColumn(modifier = Modifier.height(240.dp)) {
            items(items = names, key = { it }) { name ->
                Row(modifier = Modifier.fillMaxWidth().clickable { onSelect(name) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val image = remember(artwork, name) {
                        try { artwork?.load(name) } catch (_: Exception) { null }
                    }
                    Box(modifier = Modifier.size(32.dp)) {
                        if (image != null) Image(bitmap = image, contentDescription = null,
                            modifier = Modifier.size(32.dp), contentScale = ContentScale.Fit)
                    }
                    Text(text = if (name == selected) "Selected: $name" else name,
                        color = ForestPalette.textPrimary, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
        ForestTextAction(text = "Back without changing artwork", onClick = onCancel)
    }
}
