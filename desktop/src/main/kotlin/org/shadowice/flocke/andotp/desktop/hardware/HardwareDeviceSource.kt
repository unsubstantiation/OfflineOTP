package org.shadowice.flocke.andotp.desktop.hardware

import com.yubico.yubikit.core.YubiKeyDevice

/**
 * Discovery seam for attached security keys. The production implementation scans PC/SC terminals
 * through yubikit's desktop module; tests substitute an in-memory source, since no hardware exists
 * in the headless environment.
 */
interface HardwareDeviceSource : AutoCloseable {
    /** Currently attached, PC/SC-reachable keys; an empty list means none available. */
    fun scan(): List<YubiKeyDevice>

    override fun close()
}

/** Headless selection policy shared by the OATH and FIDO flows. */
object HardwareDeviceSelector {
    /** The first attached key, or null when the scan finds none. */
    @JvmStatic
    fun firstAvailable(source: HardwareDeviceSource): YubiKeyDevice? = source.scan().firstOrNull()

    /** True when at least one key is reachable right now. */
    @JvmStatic
    fun isKeyPresent(source: HardwareDeviceSource): Boolean = source.scan().isNotEmpty()
}
