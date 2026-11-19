@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import platform.UIKit.UIViewController

interface NewVaultRestoreActions {
    /** Null means the full restore completed; otherwise show a safe explanatory message. */
    fun restore(backupPassword: String, newPassword: String, confirmation: String): String?
    fun dismiss()
}

class NewVaultRestorePresentation(private val backupName: String) {
    private var active by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    fun close() { active = false; error = null }

    fun viewController(actions: NewVaultRestoreActions): UIViewController = ComposeUIViewController {
        ForestTheme {
            if (active) NewVaultRestoreDialog(
                backupName = backupName, busy = false, error = error,
                onChooseBackup = {}, allowChooseBackup = false,
                onRestore = { backupPassword, newPassword, confirmation ->
                    if (active) try {
                        val failure = actions.restore(backupPassword, newPassword, confirmation)
                        if (failure == null) { close(); actions.dismiss() } else error = failure
                    } catch (_: Exception) { error = "The backup could not be restored." }
                },
                onDismiss = { if (active) { close(); actions.dismiss() } })
        }
    }
}
