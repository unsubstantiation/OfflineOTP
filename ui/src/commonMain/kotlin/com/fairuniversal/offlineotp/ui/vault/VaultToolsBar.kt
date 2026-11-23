package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.ui.portable.PortableToolGroup
import com.fairuniversal.offlineotp.ui.portable.PortableToolItem

/** Thin `:ui` adapter mapping [VaultTool] groups onto [SharedVaultToolsBar] in `:ui-foundation`. */
@Composable
fun VaultToolsBar(actions: VaultActions, strings: VaultStrings) {
    val tools = VaultTool.available(actions)
    val groups = VaultTool.Group.values().mapNotNull { group ->
        val members = tools.filter { it.group == group }
        if (members.isEmpty()) null else PortableToolGroup(
            title = when (group) {
                VaultTool.Group.IMPORT -> strings.importTools
                VaultTool.Group.BACKUP -> strings.backupTools
                VaultTool.Group.SETTINGS -> strings.settingsTools
            },
            items = members.map { tool ->
                PortableToolItem(key = tool.name, label = tool.label(strings))
            }
        )
    }
    SharedVaultToolsBar(
        addLabel = strings.addEntry,
        onAdd = actions.onAddEntry,
        toolsLabel = strings.tools,
        closeLabel = strings.closeTools,
        groups = groups,
        onSelectTool = { key ->
            tools.firstOrNull { it.name == key }?.invoke(actions)
        },
        addContentDescription = strings.addEntry,
        toolsContentDescription = strings.tools
    )
}
