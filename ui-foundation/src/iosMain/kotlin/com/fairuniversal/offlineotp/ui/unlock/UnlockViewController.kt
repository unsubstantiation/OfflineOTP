@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import com.fairuniversal.offlineotp.ui.vault.AboutNavigation
import platform.UIKit.UIViewController

/** Native methods export as void-returning Swift protocol methods, not Kotlin lambda wrappers. */
interface UnlockActions {
    fun unlock(password: String)
    fun create(password: String, confirmation: String)
    fun quickUnlock()
    fun restore()
    fun about()
    fun hardwareUnlock()
    fun hardwareSubmit(pin: String, usb: Boolean)
    fun hardwareCancel()
}

/** Main-thread UIKit bridge; the native host owns authentication and app-switcher privacy. */
class UnlockPresentation {
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var creating by mutableStateOf(false)
    private var quickUnlockLabel by mutableStateOf<String?>(null)
    private var hint by mutableStateOf<String?>(null)
    private var hardwareAvailable by mutableStateOf(false)
    private var nfcAvailable by mutableStateOf(false)
    private var usbAvailable by mutableStateOf(false)
    private var hardwareVisible by mutableStateOf(false)
    private var hardwareError by mutableStateOf<String?>(null)
    private var active by mutableStateOf(true)

    fun update(busy: Boolean, error: String?, creating: Boolean, quickUnlockLabel: String?, hint: String?) {
        if (!active) return
        this.busy = busy
        this.error = error
        this.creating = creating
        this.quickUnlockLabel = quickUnlockLabel
        this.hint = hint
    }

    fun updateHardware(available: Boolean, visible: Boolean, error: String?, nfcAvailable: Boolean, usbAvailable: Boolean) {
        if (!active) return
        hardwareAvailable = available; hardwareVisible = visible; hardwareError = error
        this.nfcAvailable = nfcAvailable; this.usbAvailable = usbAvailable
    }

    fun close() { active = false; nfcAvailable = false; usbAvailable = false; hardwareAvailable = false; hardwareVisible = false; hardwareError = null; error = null; busy = false; hint = null; quickUnlockLabel = null }

    fun viewController(strings: UnlockStrings, actions: UnlockActions): UIViewController =
        ComposeUIViewController {
            ForestTheme {
                if (active) AboutNavigation(onAbout = { if (active && !busy) actions.about() }) {
                    SharedUnlockScreen(strings, onSubmit = { password ->
                        if (active && !busy && !creating) actions.unlock(password)
                    }, busy = busy, error = error, hint = hint, creating = creating,
                        onCreate = { password, confirmation ->
                            if (active && !busy && creating) actions.create(password, confirmation)
                        }, quickUnlockLabel = quickUnlockLabel ?: "",
                        onSecurityKeyUnlock = if (!hardwareAvailable) null else ({ if (active && !busy && !creating) actions.hardwareUnlock() }),
                        onRestore = if (creating) ({ if (active && !busy) actions.restore() }) else null,
                        onHardwareUnlock = if (quickUnlockLabel == null) null else ({
                            if (active && !busy && !creating) actions.quickUnlock()
                        }))
                    if (hardwareVisible) HardwareTransportDialog(busy = busy, error = hardwareError,
                        nfcAvailable = nfcAvailable, usbAvailable = usbAvailable,
                        onSubmit = { pin, usb -> if (active && !busy && !creating) actions.hardwareSubmit(pin, usb) },
                        onCancel = { if (active) actions.hardwareCancel() })
                }
            }
        }
}
