/*
 * Compile-only stand-ins for the yubikit desktop (PC/SC) module. The real artifact is declared in
 * desktop/build.gradle with its hid4java dependency excluded: OfflineOTP uses only the
 * TerminalFactory/smartcardio path, never raw HID. The portable core/oath/fido yubikit types these
 * produce already have stubs in the sibling packages, so nothing here emulates a security key.
 */
package com.yubico.yubikit.desktop

import com.yubico.yubikit.core.YubiKeyDevice
import javax.smartcardio.TerminalFactory

class UsbPcscDevice : YubiKeyDevice {
    override fun <T> openConnection(type: Class<T>): T =
        throw NotImplementedError("sandbox stub; real yubikit desktop runs under Gradle")
}

class ScanResult {
    val usbDevices: List<UsbPcscDevice> = emptyList()
}

class PcscManager(terminalFactory: TerminalFactory) : AutoCloseable {
    fun scanDevices(): ScanResult =
        throw NotImplementedError("sandbox stub; real yubikit desktop runs under Gradle")

    override fun close() {
        throw NotImplementedError("sandbox stub; real yubikit desktop runs under Gradle")
    }
}
