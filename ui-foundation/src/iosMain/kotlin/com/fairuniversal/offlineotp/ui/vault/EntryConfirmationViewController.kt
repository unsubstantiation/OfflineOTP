@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import platform.UIKit.UIViewController

/** Native methods export as void/String?-returning Swift protocol methods, not Kotlin lambdas. */
interface EntryConfirmationActions {
    /** Null means the action completed; failures must be safe user-facing messages. */
    fun confirm(): String?
    fun dismiss()
}

/**
 * Main-thread UIKit bridge for the shared confirmation dialog. The native host owns the pending
 * action and revalidates it against the live session; a failure keeps the dialog open with the
 * error appended, matching the desktop failed-request behavior.
 */
class EntryConfirmationPresentation(
    private val title: String,
    private val message: String,
    private val confirmLabel: String,
    private val dismissLabel: String
) {
    private var active by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)

    fun close() { active = false; error = null }

    fun viewController(actions: EntryConfirmationActions): UIViewController = ComposeUIViewController {
        ForestTheme {
            if (active) EntryConfirmationDialog(title, message, confirmLabel, dismissLabel,
                onConfirm = {
                    if (active) {
                        val failure = try { actions.confirm() }
                            catch (_: Exception) { "The action could not be completed. Nothing was changed." }
                        if (active) {
                            if (failure == null) { close(); actions.dismiss() } else error = failure
                        }
                    }
                },
                onDismiss = { if (active) { close(); actions.dismiss() } },
                error = error)
        }
    }
}
