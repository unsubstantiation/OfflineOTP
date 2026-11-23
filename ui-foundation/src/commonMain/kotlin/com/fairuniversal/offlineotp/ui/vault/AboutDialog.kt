package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.PortableLibraryCatalog
import com.fairuniversal.offlineotp.ui.portable.PortableLibraryNotice
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/** Shared content; only embedded build metadata and explicit external-link dispatch come from hosts. */
@Composable
fun AboutContent(
    version: String,
    onOpenLink: (String) -> Boolean,
    libraries: List<PortableLibraryNotice> = PortableLibraryCatalog.bundled
) {
    var selectedTab by remember { mutableStateOf(0) }
    var failedLink by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SharedVaultTab(label = "About", selected = selectedTab == 0, onClick = { selectedTab = 0 })
            SharedVaultTab(label = "Libraries", selected = selectedTab == 1, onClick = { selectedTab = 1 })
        }
        Column(
            modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (selectedTab == 0) {
                Text(text = AboutInfo.description, style = ForestType.body, color = ForestPalette.textPrimary)
                Text(text = "Cryptography: Argon2id + AES-256-GCM", style = ForestType.label, color = ForestPalette.textSecondary)
                Text(text = "Network access: None (100% offline)", style = ForestType.label, color = ForestPalette.textSecondary)
                Text(text = "Version: $version", style = ForestType.label, color = ForestPalette.accent)
                Text(text = "Build time: AKST (UTC−09:00)", style = ForestType.label, color = ForestPalette.textSecondary)
                Text(text = AboutInfo.copyright, style = ForestType.body, color = ForestPalette.textPrimary)
                Text(text = AboutInfo.attribution, style = ForestType.body, color = ForestPalette.textSecondary)
                AboutInfo.links.forEach { link ->
                    if (link.id == "author1") {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "Credits", style = ForestType.label, color = ForestPalette.accent)
                    }
                    TextButton(onClick = { failedLink = if (onOpenLink(link.url)) null else link.url }) {
                        Text(text = link.label, style = ForestType.label, color = ForestPalette.accent)
                    }
                }
                failedLink?.let {
                    Text(
                        text = "Could not open the external browser. Link: $it",
                        style = ForestType.label,
                        color = ForestPalette.warning
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Thumbnail disclaimer", style = ForestType.label, color = ForestPalette.accent)
                AboutInfo.thumbnailDisclaimer.forEach {
                    Text(text = it, style = ForestType.label, color = ForestPalette.textSecondary)
                }
                Text(
                    text = "Third-party libraries retain their own licenses. Switch to the Libraries tab above for open-source dependency notices; exact native artifacts vary by platform.",
                    style = ForestType.label,
                    color = ForestPalette.textHint
                )
            } else {
                Text(
                    text = "Open-source components and license notices across OfflineOTP:",
                    style = ForestType.body,
                    color = ForestPalette.textSecondary
                )
                libraries.forEach { lib ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(text = lib.name, style = ForestType.body, color = ForestPalette.textPrimary)
                        Text(text = lib.license, style = ForestType.label, color = ForestPalette.accent)
                        if (lib.scope.isNotEmpty()) {
                            Text(text = lib.scope, style = ForestType.label, color = ForestPalette.textHint)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AboutDialog(
    version: String,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Boolean,
    libraries: List<PortableLibraryNotice> = PortableLibraryCatalog.bundled
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "About OfflineOTP", style = ForestType.title, color = ForestPalette.textPrimary) },
        text = { AboutContent(version = version, onOpenLink = onOpenLink, libraries = libraries) },
        confirmButton = { ForestTextAction(text = "Close", onClick = onDismiss) }
    )
}

/** Direct navigation, outside the vault: available both before and after unlock. */
@Composable
fun AboutNavigation(onAbout: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            ForestTextAction(text = "About", onClick = onAbout, contentDescription = "About OfflineOTP")
        }
        Box(Modifier.weight(1f)) { content() }
    }
}
