// Scripted USB descriptors only; no actual USB enumeration, permission, or transport is modeled.
package android.hardware.usb

object UsbConstants {
    const val USB_CLASS_CSCID = 11
    const val USB_CLASS_HID = 3
}

class UsbInterface(val interfaceClass: Int = 0, val interfaceSubclass: Int = 0, val endpointCount: Int = 0)
class UsbDevice(val vendorId: Int = 0, val productId: Int = 0, private val interfaces: List<UsbInterface> = emptyList()) {
    val interfaceCount: Int get() = interfaces.size
    fun getInterface(index: Int): UsbInterface = interfaces[index]
}
class UsbManager {
    var deviceList: Map<String, UsbDevice> = emptyMap()
}
