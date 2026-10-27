package com.yubico.yubikit.android

import android.app.Activity
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.core.YubiKeyDevice

/** Inert API shape, not USB/NFC discovery. */
class YubiKitManager(activity: Activity) {
    companion object {
        /** Scripted host callback; it never enumerates or communicates with hardware. */
        var usbCallback: ((YubiKeyDevice) -> Unit)? = null
        var usbConfiguration: UsbConfiguration? = null
    }
    fun startUsbDiscovery(config: UsbConfiguration, callback: (YubiKeyDevice) -> Unit) {
        usbConfiguration = config
        usbCallback = callback
    }
    fun stopUsbDiscovery() { usbCallback = null; usbConfiguration = null }
    fun startNfcDiscovery(config: NfcConfiguration, activity: Activity, callback: (YubiKeyDevice) -> Unit) {}
    fun stopNfcDiscovery(activity: Activity) {}
}
