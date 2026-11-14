package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Only ephemeral menu visibility is local. Each tool still invokes the host's guarded action. */
@Composable
fun VaultToolsBar(actions: VaultActions, strings: VaultStrings) {
    var expanded by remember { mutableStateOf(false) }
    val tools = VaultTool.available(actions)
    Row(modifier = Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
        actions.onAddEntry?.let { add -> ForestTextAction(text = strings.addEntry, onClick = {
            expanded = false; add()
        }) }
        if (tools.isNotEmpty()) ForestTextAction(text = strings.tools, onClick = { expanded = true })
    }
    if (expanded && tools.isNotEmpty()) AlertDialog(
        onDismissRequest = { expanded = false }, title = { Text(text = strings.tools) },
        text = { Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            for (group in VaultTool.Group.values()) {
                val members = tools.filter { it.group == group }
                if (members.isNotEmpty()) {
                    Text(text = when (group) {
                        VaultTool.Group.IMPORT -> strings.importTools
                        VaultTool.Group.BACKUP -> strings.backupTools
                        VaultTool.Group.SETTINGS -> strings.settingsTools
                    })
                    for (tool in members) ForestTextAction(text = tool.label(strings), onClick = {
                        // Close first so a native picker or another modal does not stack over this one.
                        expanded = false
                        tool.invoke(actions)
                    })
                }
            }
        } },
        confirmButton = {},
        dismissButton = { ForestTextAction(text = strings.closeTools, onClick = { expanded = false }) })
}
