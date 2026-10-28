package org.shadowice.flocke.andotp.desktop.hardware

import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.desktop.pcsc.PcscManager

import javax.smartcardio.TerminalFactory

/**
 * [HardwareDeviceSource] over yubikit's PC/SC desktop module (com.yubico.yubikit:desktop). It only
 * enumerates CCID/USB devices reachable through the JDK's `javax.smartcardio` [TerminalFactory];
 * raw-HID transports are excluded at the dependency level in desktop/build.gradle, so non-CCID
 * devices are deliberately invisible. The returned [YubiKeyDevice] handles are consumed by the
 * shared core `HardwareOathClient`/`HardwareFidoClient`, which contain all protocol logic.
 *
 * The class is compile-verified in the sandbox against a narrow yubikit-desktop stub mirroring the
 * published 3.2.1 API and runs for real only under the Gradle build; headless tests exercise
 * [HardwareDeviceSelector] with fakes.
 */
class PcscHardwareDiscovery : HardwareDeviceSource {

    private val manager = PcscManager(TerminalFactory.getDefault())

    override fun scan(): List<YubiKeyDevice> = manager.scanDevices().usbDevices

    /**
     * yubikit's 3.2.1 PcscManager owns no closeable resource — it wraps a JDK TerminalFactory and
     * every scan produces self-closing device handles — so there is nothing to release. The method
     * remains to satisfy the shared [HardwareDeviceSource] lifecycle used by the test fakes.
     */
    override fun close() {
        // No-op: PcscManager is not Closeable in yubikit desktop 3.2.1.
    }
}
