package org.shadowice.flocke.andotp.Utilities

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.DeviceFilter
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.core.YubiKeyDevice
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One cancellable, foreground USB/NFC operation; late results are discarded. */
internal class HardwareKeyDiscovery(private val activity: Activity, private val sessionValid: () -> Boolean) : AutoCloseable {
    enum class Protocol { FIDO, OATH }
    private val manager = YubiKitManager(activity)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var operation: Operation? = null
    private var nfcActive = false
    private var resumed = false

    private class Operation(val dispatch: (YubiKeyDevice) -> Unit, private val release: () -> Unit,
                            private val cancelWork: () -> Unit) {
        val active = AtomicBoolean(true)
        val claimed = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        fun releaseOnce() { if (released.compareAndSet(false, true)) release() }
        fun abort(): Boolean {
            if (!active.compareAndSet(true, false)) return false
            try { cancelWork() } catch (_: RuntimeException) { }
            return true
        }
    }

    /** Call on the main thread. Errors are deliberately generic and contain no device data. */
    fun <T> start(protocol: Protocol, work: (YubiKeyDevice, () -> Boolean) -> T,
                  discard: (T) -> Unit, release: () -> Unit, cancelWork: () -> Unit,
                  complete: (T?, Boolean) -> Unit) {
        cancel()
        lateinit var op: Operation
        op = Operation({ device ->
            if (op.active.get() && op.claimed.compareAndSet(false, true)) {
                try {
                    worker.execute {
                        var value: T? = null
                        var succeeded = false
                        try {
                            if (op.active.get() && sessionValid()) {
                                value = work(device) { op.active.get() && sessionValid() }
                                succeeded = true
                            }
                        } catch (_: Exception) {
                            // No exception messages: they may contain key/account information.
                        } finally {
                            op.releaseOnce()
                        }
                        main.post {
                            if (op.active.compareAndSet(true, false)) {
                                stopDiscovery()
                                if (operation === op) operation = null
                                if (sessionValid()) complete(value, succeeded)
                                else {
                                    if (succeeded) {
                                        @Suppress("UNCHECKED_CAST")
                                        discard(value as T)
                                    }
                                    complete(null, false)
                                }
                            } else if (succeeded) {
                                @Suppress("UNCHECKED_CAST")
                                discard(value as T)
                            }
                        }
                    }
                } catch (_: RuntimeException) {
                    op.releaseOnce()
                }
            }
        }, release, cancelWork)
        operation = op
        val usbManager = activity.getSystemService(Context.USB_SERVICE) as? UsbManager
        // Detach broadcasts can arrive after the device disappears from deviceList. Retain IDs
        // seen during this operation so the SDK can close detached devices and their resources.
        val seenUsbIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Int, Int>>()
        val filter = object : DeviceFilter() {
            override fun checkVendorProductIds(vendorId: Int, productId: Int): Boolean {
                val id = vendorId to productId
                if (usbManager?.deviceList?.values?.any {
                    it.vendorId == vendorId && it.productId == productId && supports(it, protocol)
                } == true) seenUsbIds.add(id)
                return id in seenUsbIds
            }
            override fun checkUsbDevice(usbManager: UsbManager, usbDevice: UsbDevice): Boolean =
                supports(usbDevice, protocol)
        }
        try {
            manager.startUsbDiscovery(UsbConfiguration().setDeviceFilter(filter)) { op.dispatch(it) }
        } catch (_: RuntimeException) {
            // A phone without USB host support can still use NFC.
        }
        if (resumed) startNfc(op)
        main.postDelayed({
            if (op.abort()) {
                if (!op.claimed.get()) op.releaseOnce()
                stopDiscovery()
                if (operation === op) operation = null
                complete(null, false)
            }
        }, 60_000)
    }

    fun resume() {
        resumed = true
        operation?.let { if (it.active.get()) startNfc(it) }
    }

    fun pause() {
        resumed = false
        stopNfc()
    }

    private fun startNfc(op: Operation) {
        if (nfcActive || !op.active.get()) return
        try {
            manager.startNfcDiscovery(NfcConfiguration().timeout(30_000), activity) { op.dispatch(it) }
            nfcActive = true
        } catch (_: NfcNotAvailable) {
            // USB can still be used.
        } catch (_: RuntimeException) { }
    }

    private fun stopNfc() {
        if (!nfcActive) return
        nfcActive = false
        try { manager.stopNfcDiscovery(activity) } catch (_: RuntimeException) { }
    }

    private fun stopDiscovery() {
        stopNfc()
        try { manager.stopUsbDiscovery() } catch (_: RuntimeException) { }
    }

    fun cancel() {
        operation?.let { op ->
            op.abort()
            if (!op.claimed.get()) op.releaseOnce()
        }
        operation = null
        stopDiscovery()
    }

    override fun close() {
        cancel()
        worker.shutdownNow()
    }

    companion object {
        private fun supports(device: UsbDevice, protocol: Protocol): Boolean {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (protocol == Protocol.OATH && iface.interfaceClass == UsbConstants.USB_CLASS_CSCID &&
                    iface.interfaceSubclass == 0) return true
                if (protocol == Protocol.FIDO && iface.interfaceClass == UsbConstants.USB_CLASS_HID &&
                    iface.interfaceSubclass == 0 && iface.endpointCount >= 2) return true
            }
            return false
        }
    }
}
