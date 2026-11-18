@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import platform.UIKit.UIViewController

/** Native methods export as void/boolean-returning Swift protocol methods, not Kotlin lambdas. */
interface PreferencesImportActions {
    /** Applies the validated file. True only when the settings were written. */
    fun confirm(): Boolean
    fun dismiss()
}

/**
 * Main-thread UIKit bridge for the shared import confirmation. The native host owns file picking,
 * validation and the settings port; this bridge only carries the plan counts and failure state.
 */
class PreferencesImportPresentation {
    private var active by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var count by mutableStateOf<Int?>(null)
    private var skipped by mutableStateOf(0)
    private var failed by mutableStateOf(false)
    private var errorText by mutableStateOf<String?>(null)

    /** [count] below zero means no validated plan; a nullable Int would export as NSNumber?. */
    fun update(busy: Boolean, count: Int, skipped: Int, failed: Boolean, errorText: String?) {
        if (!active) return
        this.busy = busy
        this.count = if (count >= 0) count else null
        this.skipped = skipped
        this.failed = failed
        this.errorText = errorText
    }

    fun close() {
        active = false
        busy = false
        count = null
        skipped = 0
        failed = false
        errorText = null
    }

    fun viewController(actions: PreferencesImportActions): UIViewController = ComposeUIViewController {
        ForestTheme {
            if (active) PreferencesImportDialog(busy, count, skipped, failed,
                onConfirm = {
                    if (active && !busy && count != null && !failed) {
                        busy = true
                        val applied = try { actions.confirm() } catch (_: Exception) { false }
                        busy = false
                        if (active) {
                            if (applied) { close(); actions.dismiss() }
                            else { failed = true; errorText = null }
                        }
                    }
                },
                onDismiss = { if (active && !busy) { close(); actions.dismiss() } },
                platformName = "iOS",
                applyDetail = "Apply updates the current sort order and auto-lock timeout when included. A file may disable auto-lock; import only preferences you trust.",
                failureText = errorText)
        }
    }
}
