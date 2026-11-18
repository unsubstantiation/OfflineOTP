@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import platform.UIKit.UIViewController

interface PasswordChangeActions {
    /** Null means success; failures must be safe user-facing messages, never password values. */
    fun change(current: String, next: String, confirmation: String): String?
    fun successMessage(): String
    fun dismiss()
}

/** Main-thread adapter to iOS's synchronous rekey operation; retains no password fields. */
class PasswordChangePresentation(private val maxFieldChars: Int) {
    private var active by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var result by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)

    fun close() { active = false; result = null; error = null }

    fun viewController(actions: PasswordChangeActions): UIViewController = ComposeUIViewController {
        ForestTheme {
            if (active) PasswordChangeDialog(this, busy, result, invalid = error != null,
                maxFieldChars = maxFieldChars, requiresUnlock = false, errorText = error,
                onSubmit = { current, next, confirmation ->
                    if (active && !busy && result == null) {
                        if (current.isEmpty() || next.isEmpty() || next == current || next != confirmation ||
                            listOf(current, next, confirmation).any { it.length > maxFieldChars }) {
                            error = "Enter the current password and a different new password with matching confirmation."
                        } else {
                            busy = true
                            val failure = try { actions.change(current, next, confirmation) }
                                catch (_: Exception) { "The vault password could not be updated." }
                            busy = false
                            if (active) {
                                error = failure
                                if (failure == null) result = actions.successMessage()
                            }
                        }
                    }
                }, onDismiss = { if (active && !busy) { close(); actions.dismiss() } })
        }
    }
}
