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

/** Host-supplied timeout and background-relock options; opening/dismissing never changes a setting. */
@Composable
fun AutoLockDialog(
    inputKey: Any,
    currentSeconds: Int,
    options: List<Int>,
    failed: Boolean,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
    relockOnBackground: Boolean? = null,
    relockOnBackgroundLabel: String = "Lock immediately when app is backgrounded or minimized",
    onSaveWithBackground: ((Int, Boolean) -> Unit)? = null
) {
    var selected by remember(inputKey) { mutableStateOf(currentSeconds) }
    var relockChecked by remember(inputKey) { mutableStateOf(relockOnBackground == true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Auto-lock timeout") },
        text = { ScrollableDialogBody {
                Text(text = "Lock after inactivity. Disabled keeps the vault open until you lock or close it.")
                for (seconds in (options + currentSeconds).distinct()) {
                    val label = AutoLockOptions.label(seconds)
                    ForestTextAction(
                        text = if (seconds == selected) "Selected: $label" else label,
                        onClick = { selected = seconds }
                    )
                }
                if (relockOnBackground != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { relockChecked = !relockChecked }
                            .padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = relockChecked,
                            onCheckedChange = { relockChecked = it }
                        )
                        Text(text = relockOnBackgroundLabel)
                    }
                }
                if (failed) SharedStatusMessage("Could not save the setting. The previous timeout is still active.")
            } },
        confirmButton = {
            ForestTextAction(
                text = "Save",
                onClick = {
                    if (onSaveWithBackground != null) {
                        onSaveWithBackground(selected, relockChecked)
                    } else {
                        onSave(selected)
                    }
                }
            )
        },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = onDismiss) }
    )
}
