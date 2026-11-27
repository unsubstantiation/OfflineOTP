package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.VaultContainer
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class VaultContainerContractTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun independentArgonAndLegacyVectorsOpenAsVaultAndPortableRestore() {
        val root = javaClass.getResourceAsStream("/golden/vault-container.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val rows = root.getJSONArray("vectors")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val file = hex(row.getString("fileHex")); val key = hex(row.getString("keyHex"))
            val password = row.getString("password"); val plain = row.getString("plaintext")
            assertEquals(plain, String(VaultContainer.open(password, file)))
            assertEquals(plain, String(VaultContainer.openWithKey(SecretKeySpec(key, "AES"), file)))
            assertEquals(plain, EncryptedBackupCodec.open(password, file))
            val rewritten = VaultContainer.seal(SecretKeySpec(key, "AES"), VaultContainer.header(file), plain.toByteArray())
            assertTrue(!file.contentEquals(rewritten)); assertEquals(plain, String(VaultContainer.open(password, rewritten)))
        }
    }
}
