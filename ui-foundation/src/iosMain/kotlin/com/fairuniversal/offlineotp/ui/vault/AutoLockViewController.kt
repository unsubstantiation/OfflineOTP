@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import platform.UIKit.UIViewController

interface AutoLockActions {
    fun save(seconds: Int): Boolean
    fun dismiss()
}

/** A single main-thread, session-owned settings request. Hosts must close it on session change. */
class AutoLockPresentation(private val currentSeconds: Int) {
    private var active by mutableStateOf(true)
    private var failed by mutableStateOf(false)
    fun close() { active = false; failed = false }

    fun viewController(actions: AutoLockActions): UIViewController = ComposeUIViewController {
        ForestTheme {
            if (active) AutoLockDialog(this, currentSeconds, AutoLockOptions.seconds, failed,
                onSave = { seconds ->
                    if (active && seconds in AutoLockOptions.including(currentSeconds)) {
                        val saved = actions.save(seconds)
                        if (active) {
                            if (saved) { close(); actions.dismiss() } else failed = true
                        }
                    }
                }, onDismiss = { if (active) { close(); actions.dismiss() } })
        }
    }
}
