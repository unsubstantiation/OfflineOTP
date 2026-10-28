package org.shadowice.flocke.andotp.desktop.hardware

import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.desktop.PcscManager

import javax.smartcardio.TerminalFactory

/**
 * [HardwareDeviceSource] over yubikit's PC/SC desktop module. It only enumerates CCID/USB devices
 * reachable through the JDK's `javax.smartcardio` [TerminalFactory]; raw-HID transports are excluded
 * at the dependency level in desktop/build.gradle, so non-CCID devices are deliberately invisible.
 * The returned [YubiKeyDevice] handles are consumed by the shared core `HardwareOathClient`/
 * `HardwareFidoClient`, which contain all protocol logic.
 *
 * The class is compile-verified in the sandbox against a narrow yubikit-desktop stub and runs for
 * real only under the Gradle build; headless tests exercise [HardwareDeviceSelector] with fakes.
 */
class PcscHardwareDiscovery : HardwareDeviceSource {

    private val manager = PcscManager(TerminalFactory.getDefault())

    override fun scan(): List<YubiKeyDevice> = manager.scanDevices().usbDevices

    override fun close() {
        manager.close()
    }
}
