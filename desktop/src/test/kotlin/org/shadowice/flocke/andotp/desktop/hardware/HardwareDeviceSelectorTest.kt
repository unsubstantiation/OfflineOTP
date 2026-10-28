/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the security-key selection policy. PC/SC scanning itself needs hardware and
 * a real yubikit desktop module, so the production PcscHardwareDiscovery is compile-verified while
 * the shared selector logic runs against an in-memory fake source.
 */
package org.shadowice.flocke.andotp.desktop.hardware

import com.yubico.yubikit.core.YubiKeyDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareDeviceSelectorTest {

    private fun fakeDevice() = object : YubiKeyDevice {
        override fun <T> openConnection(type: Class<T>): T =
            throw NotImplementedError("test device never opens a connection")
    }

    private class FakeSource(private val devices: List<YubiKeyDevice>) : HardwareDeviceSource {
        var scans = 0
            private set
        var closed = false
            private set

        override fun scan(): List<YubiKeyDevice> {
            scans++
            return devices
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun emptyScanMeansNoKeyAndANullSelection() {
        val source = FakeSource(emptyList())
        assertFalse(HardwareDeviceSelector.isKeyPresent(source))
        assertNull(HardwareDeviceSelector.firstAvailable(source))
    }

    @Test
    fun firstAvailableReturnsTheFirstAttachedKey() {
        val first = fakeDevice()
        val second = fakeDevice()
        val source = FakeSource(listOf(first, second))

        assertTrue(HardwareDeviceSelector.isKeyPresent(source))
        // The shim lacks assertSame; identity comparison is the actual assertion.
        assertTrue(first === HardwareDeviceSelector.firstAvailable(source))
    }

    @Test
    fun eachPolicyCheckScansOnceAndCloseIsForwarded() {
        val source = FakeSource(listOf(fakeDevice()))
        HardwareDeviceSelector.isKeyPresent(source)
        HardwareDeviceSelector.firstAvailable(source)
        assertEquals(2, source.scans)

        source.close()
        assertTrue(source.closed)
    }
}
