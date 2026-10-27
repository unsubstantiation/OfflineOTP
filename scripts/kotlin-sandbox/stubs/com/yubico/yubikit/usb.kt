package com.yubico.yubikit.android.transport.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

open class DeviceFilter {
    open fun checkVendorProductIds(vendorId: Int, productId: Int): Boolean = true
    open fun checkUsbDevice(usbManager: UsbManager, usbDevice: UsbDevice): Boolean = true
}
class UsbConfiguration {
    var filter: DeviceFilter? = null
        private set
    fun setDeviceFilter(filter: DeviceFilter): UsbConfiguration { this.filter = filter; return this }
}
