@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import platform.UIKit.UIViewController

interface BiometricEnableActions {
    fun confirm(password: String): String?
    fun dismiss()
}

class BiometricEnablePresentation(
    private val title: String,
    private val detail: String,
    private val passwordLabel: String,
    private val confirmLabel: String,
    private val cancelLabel: String
) {
    private var active by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    fun close() { active = false; error = null }

    fun viewController(actions: BiometricEnableActions): UIViewController = ComposeUIViewController {
        ForestTheme {
            if (active) BiometricEnableDialog(title, detail, passwordLabel, confirmLabel, cancelLabel, error,
                onConfirm = { password ->
                    if (!active) null else try {
                        val failure = actions.confirm(password)
                        if (failure == null) { close(); actions.dismiss() } else error = failure
                        failure
                    } catch (_: Exception) { "Biometric unlock could not be enabled.".also { error = it } }
                },
                onDismiss = { if (active) { close(); actions.dismiss() } })
        }
    }
}
