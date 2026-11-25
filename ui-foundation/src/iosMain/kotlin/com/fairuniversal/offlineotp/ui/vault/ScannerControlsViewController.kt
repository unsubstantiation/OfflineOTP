@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import platform.UIKit.UIViewController

interface ScannerControlActions { fun cancel() }

/** App-owned camera action; the camera/permissions/guidance remain VisionKit-owned. */
class ScannerControlsPresentation {
    private var active = true
    fun close() { active = false }
    fun viewController(actions: ScannerControlActions): UIViewController = ComposeUIViewController {
        ForestTheme {
            Row(Modifier.padding(8.dp)) {
                ForestTextAction("Cancel", onClick = { if (active) { active = false; actions.cancel() } })
            }
        }
    }
}
