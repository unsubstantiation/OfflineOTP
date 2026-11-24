package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * Shared multi-choice checklist dialog for account/record export selection.
 *
 * Only display labels enter this form; secret payloads remain owned by the host until confirmation.
 */
@Composable
fun MultiChoiceSelectionDialog(
    inputKey: Any,
    title: String,
    message: String,
    items: List<String>,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
    initialSelected: Set<Int> = emptySet()
) {
    var selected by remember(inputKey) {
        mutableStateOf(initialSelected.filter { it in items.indices }.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = ForestType.title, color = ForestPalette.textPrimary) },
        text = {
            ScrollableDialogBody {
                if (message.isNotEmpty()) {
                    Text(text = message, style = ForestType.body, color = ForestPalette.textSecondary)
                    Spacer(Modifier.height(8.dp))
                }
                items.forEachIndexed { index, label ->
                    val checked = index in selected
                    val toggle = {
                        selected = if (checked) selected - index else selected + index
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = toggle)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                        Spacer(Modifier.width(8.dp))
                        Text(text = label, style = ForestType.body, color = ForestPalette.textPrimary)
                    }
                }
            }
        },
        confirmButton = {
            ForestTextAction(
                text = confirmLabel,
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(selected) }
            )
        },
        dismissButton = {
            ForestTextAction(text = dismissLabel, onClick = onDismiss)
        }
    )
}

/** Shared single-choice preference form. Opening or dismissing never commits a value. */
@Composable
fun SingleChoiceSelectionDialog(
    inputKey: Any,
    title: String,
    items: List<String>,
    initialSelected: Int,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(inputKey) { mutableStateOf(initialSelected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { ScrollableDialogBody {
            items.forEachIndexed { index, label ->
                ForestTextAction(text = if (index == selected) "Selected: $label" else label,
                    onClick = { selected = index })
            }
        } },
        confirmButton = { ForestTextAction(text = confirmLabel, enabled = selected in items.indices,
            onClick = { if (selected in items.indices) onConfirm(selected) }) },
        dismissButton = { ForestTextAction(text = dismissLabel, onClick = onDismiss) }
    )
}
