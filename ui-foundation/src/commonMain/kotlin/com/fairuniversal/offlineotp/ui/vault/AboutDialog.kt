package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
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

/** Shared content; only embedded build metadata and explicit external-link dispatch come from hosts. */
@Composable
fun AboutContent(version: String, onOpenLink: (String) -> Boolean) {
    var failedLink by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(AboutInfo.description)
        Text("Cryptography: Argon2id + AES-256-GCM")
        Text("Network access: None (100% offline)")
        Text("Version: $version")
        Text("Build time: AKST (UTC−09:00)")
        Text(AboutInfo.copyright)
        Text(AboutInfo.attribution)
        AboutInfo.links.forEach { link ->
            if (link.id == "author1") Text("Credits")
            TextButton(onClick = { failedLink = if (onOpenLink(link.url)) null else link.url }) { Text(link.label) }
        }
        failedLink?.let { Text("Could not open the external browser. Link: $it") }
        Text("Thumbnail disclaimer")
        AboutInfo.thumbnailDisclaimer.forEach { Text(it) }
        Text("Third-party libraries retain their own licenses. Android's Libraries tab lists its bundled dependencies; dependency lists differ by platform.")
    }
}

@Composable
fun AboutDialog(version: String, onDismiss: () -> Unit, onOpenLink: (String) -> Boolean) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("About OfflineOTP") },
        text = { AboutContent(version, onOpenLink) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/** Direct navigation, outside the vault: available both before and after unlock. */
@Composable
fun AboutNavigation(onAbout: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onAbout) { Text("About") }
        Box(Modifier.weight(1f)) { content() }
    }
}
