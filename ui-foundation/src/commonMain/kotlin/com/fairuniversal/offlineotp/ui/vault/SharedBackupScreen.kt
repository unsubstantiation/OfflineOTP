package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.PortableToolGroup
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/** Engine-free full-page backup overview. Hosts own format availability, picker I/O and task state. */
@Composable
fun SharedBackupScreen(
    title: String,
    description: String,
    groups: List<PortableToolGroup>,
    warning: String,
    busy: Boolean,
    onSelectTool: (String) -> Unit
) {
    SharedVaultToolsScreen(title, description, groups, warning, busy, onSelectTool)
}

/** Shared full-page grouped settings/tools surface; format and platform policy stay in the host. */
@Composable
fun SharedVaultToolsScreen(
    title: String,
    description: String,
    groups: List<PortableToolGroup>,
    warning: String,
    busy: Boolean,
    onSelectTool: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(ForestPalette.backgroundDeep)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = title, style = ForestType.title, color = ForestPalette.textPrimary)
        Text(text = description, style = ForestType.body, color = ForestPalette.textSecondary)
        if (busy) LinearProgressIndicator()
        SharedVaultToolGroups(groups = groups, message = warning, onSelectTool = onSelectTool)
    }
}
