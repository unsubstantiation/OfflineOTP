/*
 * Compile-only stand-ins for the yubikit desktop PC/SC module as published in
 * com.yubico.yubikit:desktop:3.2.1. In 3.2.1 these classes live in the
 * com.yubico.yubikit.desktop.pcsc sub-package (not com.yubico.yubikit.desktop), scanDevices()
 * returns a nested PcscDevices holder that partitions USB and NFC readers, and PcscManager itself
 * is not Closeable: it only wraps a JDK TerminalFactory and each scan returns self-closing device
 * handles. The real artifact is declared in desktop/build.gradle with its hid4java dependency
 * excluded; OfflineOTP uses only the TerminalFactory/smartcardio path, never raw HID.
 */
package com.yubico.yubikit.desktop.pcsc

import com.yubico.yubikit.core.YubiKeyDevice
import javax.smartcardio.TerminalFactory

/** Real UsbPcscDevice extends PcscDevice, which implements the core YubiKeyDevice surface. */
class UsbPcscDevice : YubiKeyDevice {
    override fun <T> openConnection(type: Class<T>): T =
        throw NotImplementedError("sandbox stub; real yubikit desktop runs under Gradle")
}

/** Result of one scan, partitioning card-present terminals into USB YubiKeys and NFC readers. */
class PcscDevices internal constructor(
    @get:JvmName("getUsbDevices") val usbDevices: List<UsbPcscDevice>
)

class PcscManager(private val terminalFactory: TerminalFactory) {

    constructor() : this(TerminalFactory.getDefault())

    fun scanDevices(): PcscDevices =
        throw NotImplementedError("sandbox stub; real yubikit desktop runs under Gradle")
}
