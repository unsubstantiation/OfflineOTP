package com.fairuniversal.offlineotp.Utilities

import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultContainerTest {
    private class Settings : KeyValueSettings {
        val strings = mutableMapOf<String, String>(); val ints = mutableMapOf<String, Int>()
        override fun getString(key: String, default: String) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getInt(key: String, default: Int) = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun remove(key: String) { strings.remove(key); ints.remove(key) }
    }
    private fun header(): VaultContainer.Header = VaultContainer.fromSettings(Settings().apply {
        strings[VaultSettingsKeys.AUTH_KDF] = Constants.KDF_ARGON2ID
        strings[VaultSettingsKeys.AUTH_SALT] = Base64.getUrlEncoder().encodeToString(ByteArray(16) { it.toByte() })
        ints[VaultSettingsKeys.AUTH_MEMORY_KIB] = 32
        ints[VaultSettingsKeys.AUTH_ITERATIONS] = 1
        ints[VaultSettingsKeys.AUTH_PARALLELISM] = 1
    })
    private fun rejected(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: Exception) { failed = true }
        assertTrue(failed)
    }
    @Test fun authenticatesHeaderAndBodyAndRequiresRecoveryPassword() {
        val h = header(); val key = VaultContainer.deriveKey("recovery", h)
        val file = VaultContainer.seal(key, h, "[]".toByteArray())
        assertEquals("[]", String(VaultContainer.open("recovery", file)))
        rejected { VaultContainer.open("wrong", file) }
        rejected { VaultContainer.open("", file) }
        // Every metadata byte, nonce, body and tag is either rejected or authenticated.
        for (offset in file.indices) {
            val changed = file.copyOf(); changed[offset] = (changed[offset].toInt() xor 1).toByte()
            rejected { VaultContainer.openWithKey(key, changed) }
        }
        assertTrue(!file.contentEquals(VaultContainer.seal(key, h, "[]".toByteArray())))
        rejected { VaultContainer.openWithKey(SecretKeySpec(ByteArray(16), "AES"), file) }
    }
    @Test fun strictBoundsVersionsAndTruncationRejectBeforeKeyDerivation() {
        val h = header(); val key = VaultContainer.deriveKey("recovery", h)
        val file = VaultContainer.seal(key, h, "[]".toByteArray())
        for (length in 0 until VaultContainer.OVERHEAD_BYTES + 1) rejected { VaultContainer.header(file.copyOf(length)) }
        for ((offset, value) in listOf(8 to 2, 9 to 2, 10 to 99, 11 to 1)) {
            val changed = file.copyOf(); changed[offset] = value.toByte()
            assertTrue(VaultContainer.isContainer(changed)); rejected { VaultContainer.header(changed) }
        }
        for (offset in listOf(12, 16, 20)) {
            val changed = file.copyOf(); ByteBuffer.wrap(changed).putInt(offset, Int.MAX_VALUE)
            rejected { VaultContainer.header(changed) }
        }
        rejected { VaultContainer.header(ByteArray(BoundedInputReader.MAX_DOCUMENT_BYTES + 1)) }
        rejected { VaultContainer.seal(key, h, byteArrayOf()) }
    }
}
