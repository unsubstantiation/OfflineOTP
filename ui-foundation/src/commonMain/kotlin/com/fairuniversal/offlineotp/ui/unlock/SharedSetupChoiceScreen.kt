package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.theme.ForestType

/** Reusable setup step. Hosts own supported choices, validation and non-secret restoration. */
@Composable
fun SharedSetupChoiceScreen(title: String, description: String, choices: List<String>, selected: Int,
                            selectionDescription: String, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = title, style = ForestType.title)
        Text(text = description)
        choices.forEachIndexed { index, label ->
            com.fairuniversal.offlineotp.ui.vault.ForestTextAction(text = if (index == selected) "Selected: $label" else label,
                onClick = { onSelect(index) })
        }
        if (selectionDescription.isNotEmpty()) Text(text = selectionDescription)
    }
}

/** Explicit create/restore choice and result state; provisioning remains a host transaction. */
@Composable
fun SharedSetupCompletionScreen(title: String, message: String, createLabel: String, restoreLabel: String,
                                busy: Boolean, completed: Boolean, onCreate: () -> Unit, onRestore: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = title, style = ForestType.title)
        Text(text = message)
        if (busy) androidx.compose.material3.LinearProgressIndicator()
        if (!completed) {
            com.fairuniversal.offlineotp.ui.vault.ForestTextAction(text = createLabel, enabled = !busy, onClick = onCreate)
            com.fairuniversal.offlineotp.ui.vault.ForestTextAction(text = restoreLabel, enabled = !busy, onClick = onRestore)
        }
    }
}
