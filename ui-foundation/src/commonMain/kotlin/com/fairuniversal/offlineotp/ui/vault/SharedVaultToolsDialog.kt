package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.PortableToolGroup
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/**
 * Shared vault secondary-tools bar across platforms: primary Add button plus grouped Tools modal.
 *
 * Only ephemeral menu visibility is local; closing the modal happens before dispatching [onSelectTool]
 * so native file pickers or nested dialogs never stack behind an open tools sheet.
 */
@Composable
fun SharedVaultToolsBar(
    addLabel: String,
    onAdd: (() -> Unit)?,
    toolsLabel: String,
    closeLabel: String,
    groups: List<PortableToolGroup>,
    onSelectTool: (String) -> Unit,
    horizontalPaddingDp: Int = 16,
    addContentDescription: String? = null,
    toolsContentDescription: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val nonEmptyGroups = remember(groups) { groups.filter { it.items.isNotEmpty() } }
    Row(modifier = Modifier.padding(horizontal = horizontalPaddingDp.dp).horizontalScroll(rememberScrollState())) {
        onAdd?.let { add ->
            ForestTextAction(
                text = addLabel,
                onClick = {
                    expanded = false
                    add()
                },
                contentDescription = addContentDescription
            )
        }
        if (nonEmptyGroups.isNotEmpty()) {
            ForestTextAction(
                text = toolsLabel,
                onClick = { expanded = true },
                contentDescription = toolsContentDescription
            )
        }
    }
    if (expanded && nonEmptyGroups.isNotEmpty()) {
        SharedVaultToolsDialog(
            title = toolsLabel,
            groups = nonEmptyGroups,
            closeLabel = closeLabel,
            onSelectTool = { key ->
                expanded = false
                onSelectTool(key)
            },
            onDismiss = { expanded = false }
        )
    }
}

/** Shared grouped tools/settings dialog across Android, Desktop and iOS. */
@Composable
fun SharedVaultToolsDialog(
    title: String,
    groups: List<PortableToolGroup>,
    closeLabel: String = "Close",
    message: String = "",
    onSelectTool: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = ForestType.title, color = ForestPalette.textPrimary) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (group in groups) {
                    if (group.items.isEmpty()) continue
                    if (group.title.isNotEmpty()) {
                        Text(text = group.title, style = ForestType.label, color = ForestPalette.accent)
                    }
                    for (item in group.items) {
                        if (!item.interactive) {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(text = item.label, style = ForestType.body, color = ForestPalette.textPrimary)
                                if (item.value.isNotEmpty()) {
                                    Text(text = item.value, style = ForestType.label, color = ForestPalette.textSecondary)
                                }
                            }
                        } else if (item.detail.isEmpty() && item.value.isEmpty()) {
                            ForestTextAction(
                                text = item.label,
                                enabled = item.enabled,
                                onClick = { onSelectTool(item.key) }
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(item.enabled) { onSelectTool(item.key) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.label,
                                        style = ForestType.body,
                                        color = if (item.enabled) ForestPalette.textPrimary else ForestPalette.textHint
                                    )
                                    if (item.detail.isNotEmpty()) {
                                        Text(text = item.detail, style = ForestType.label, color = ForestPalette.textSecondary)
                                    }
                                }
                                if (item.value.isNotEmpty()) {
                                    Text(text = item.value, style = ForestType.label, color = ForestPalette.textSecondary)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
                if (message.isNotEmpty()) {
                    Text(text = message, style = ForestType.body, color = ForestPalette.warning)
                }
            }
        },
        confirmButton = {},
        dismissButton = { ForestTextAction(text = closeLabel, onClick = onDismiss) }
    )
}
