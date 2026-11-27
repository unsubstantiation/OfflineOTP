package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.HardwareUnlockEnvelope
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareUnlockEnvelopeContractTest {
    @Test fun syntheticPythonVectorsMatchJVMAndSwiftFormat() {
        val root = javaClass.getResourceAsStream("/golden/hardware-unlock-envelope.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val rows = root.getJSONArray("vectors")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            fun bytes(key: String) = Base64.getDecoder().decode(row.getString(key))
            val nonce = bytes("nonce")
            val random = object : SecureRandom() { override fun nextBytes(bytes: ByteArray) { nonce.copyInto(bytes) } }
            val expected = bytes("envelope"); val key = bytes("vaultKey"); val prf = bytes("prf")
            assertArrayEquals(expected, HardwareUnlockEnvelope.seal(bytes("credentialId"), bytes("salt"), prf, key, random))
            assertArrayEquals(key, HardwareUnlockEnvelope.open(expected, prf))
            assertArrayEquals(bytes("credentialId"), HardwareUnlockEnvelope.request(expected)!!.credentialId)
            for (offset in expected.indices) {
                val changed = expected.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
                assertNull(HardwareUnlockEnvelope.open(changed, prf))
            }
        }
    }
}
