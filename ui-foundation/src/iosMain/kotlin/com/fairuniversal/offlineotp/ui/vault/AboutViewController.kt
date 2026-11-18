@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import platform.UIKit.UIViewController

interface AboutActions {
    fun openLink(url: String): Boolean
    fun dismiss()
}

/** Main-thread lifecycle bridge, not a second About layout or content catalogue. */
class AboutPresentation {
    private var active by mutableStateOf(true)
    fun close() { active = false }

    fun viewController(version: String, actions: AboutActions): UIViewController = ComposeUIViewController {
        ForestTheme {
            if (active) AboutDialog(version, onDismiss = {
                if (active) { close(); actions.dismiss() }
            }, onOpenLink = { url -> active && actions.openLink(url) })
        }
    }
}
