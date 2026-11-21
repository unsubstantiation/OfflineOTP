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
import com.fairuniversal.offlineotp.ui.portable.PortableCardLayout

/** Shared Compose vault card layout and display selector across Desktop and iOS. */
@Composable
fun SharedCardLayoutDialog(
    inputKey: Any,
    current: PortableCardLayout,
    failed: Boolean,
    onSave: (PortableCardLayout) -> Unit,
    onDismiss: () -> Unit,
    hideIssuer: Boolean? = null,
    onSaveAppearance: ((PortableCardLayout, Boolean) -> Unit)? = null,
    showPreviousToken: Boolean? = null,
    tokenGroupSize: Int? = null,
    allowDefaultGrouping: Boolean = true,
    onSaveDisplay: ((PortableCardLayout, Boolean, Boolean, Int?) -> Unit)? = null,
    showIndividualCountdowns: Boolean? = null,
    onSaveCardDisplay: ((PortableCardLayout, Boolean, Boolean, Int?, Boolean) -> Unit)? = null
) {
    var selected by remember(inputKey) { mutableStateOf(current) }
    var hideIssuerChecked by remember(inputKey) { mutableStateOf(hideIssuer == true) }
    var showPreviousChecked by remember(inputKey) { mutableStateOf(showPreviousToken != false) }
    var selectedGroupSize by remember(inputKey) { mutableStateOf(tokenGroupSize) }
    var showIndividualChecked by remember(inputKey) { mutableStateOf(showIndividualCountdowns != false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Card layout") },
        text = {
            ScrollableDialogBody {
                Text(text = "Choose the Compose vault card layout. Saving keeps your current filter and revealed entries.")
                for (layout in PortableCardLayout.values()) {
                    val label = when (layout) {
                        PortableCardLayout.DEFAULT -> "Default"
                        PortableCardLayout.COMPACT -> "Compact"
                        PortableCardLayout.FULL -> "Full"
                    }
                    ForestTextAction(
                        text = if (selected == layout) "Selected: $label" else label,
                        onClick = { selected = layout }
                    )
                }
                if (onSaveDisplay != null || onSaveCardDisplay != null) {
                    Text(
                        text = "Token grouping",
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    val groupOptions = buildList<Pair<Int?, String>> {
                        if (allowDefaultGrouping) add(null to "Default")
                        add(0 to "Don't split")
                        add(2 to "After two characters")
                        add(3 to "After three characters")
                    }
                    for ((size, label) in groupOptions) {
                        ForestTextAction(
                            text = if (selectedGroupSize == size) "Selected: $label" else label,
                            onClick = { selectedGroupSize = size }
                        )
                    }
                }
                if (hideIssuer != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { hideIssuerChecked = !hideIssuerChecked }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = hideIssuerChecked,
                            onCheckedChange = { hideIssuerChecked = it }
                        )
                        Text(text = "Hide issuer label on cards")
                    }
                }
                if (showPreviousToken != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPreviousChecked = !showPreviousChecked }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = showPreviousChecked,
                            onCheckedChange = { showPreviousChecked = it }
                        )
                        Text(text = "Show previous code when revealed")
                    }
                }
                if (showIndividualCountdowns != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showIndividualChecked = !showIndividualChecked }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = showIndividualChecked,
                            onCheckedChange = { showIndividualChecked = it }
                        )
                        Text(text = "Show individual countdown timers")
                    }
                }
                if (failed) Text(text = "Could not save the layout. The current layout has not changed.")
            }
        },
        confirmButton = {
            ForestTextAction(text = "Save", onClick = {
                when {
                    onSaveCardDisplay != null -> onSaveCardDisplay(
                        selected,
                        hideIssuerChecked,
                        showPreviousChecked,
                        selectedGroupSize,
                        showIndividualChecked
                    )
                    onSaveDisplay != null -> onSaveDisplay(selected, hideIssuerChecked, showPreviousChecked, selectedGroupSize)
                    onSaveAppearance != null && hideIssuer != null -> onSaveAppearance(selected, hideIssuerChecked)
                    else -> onSave(selected)
                }
            })
        },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) }
    )
}
