@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import platform.UIKit.UIViewController

/**
 * UIKit bridge for the shared screen. Invoke on the main thread. The host supplies authentication
 * and owns lifecycle/privacy covering. This bridge is not yet installed in the release iOS shell.
 */
class UnlockPresentation {
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var active by mutableStateOf(true)

    fun update(busy: Boolean, error: String?) {
        if (!active) return
        this.busy = busy
        this.error = error
    }

    /** Dispose the form state and revoke submissions before releasing its native host. */
    fun close() { active = false; error = null; busy = false }

    fun viewController(strings: UnlockStrings, submit: (String) -> Unit): UIViewController =
        ComposeUIViewController {
            ForestTheme {
                if (active) SharedUnlockScreen(strings, onSubmit = { password ->
                    if (active && !busy) submit(password)
                }, busy = busy, error = error)
            }
        }
}
