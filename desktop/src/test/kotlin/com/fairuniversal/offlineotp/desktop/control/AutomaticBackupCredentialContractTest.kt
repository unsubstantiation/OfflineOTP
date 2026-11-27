package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.AutomaticBackupCredentialCodec
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticBackupCredentialContractTest {
    @Test fun independentCiphertextVectorsMatchAndConfigurationIsValidated() {
        val json = javaClass.getResourceAsStream("/golden/automatic-backup-credentials.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val rows = json.getJSONArray("vectors")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            fun bytes(key: String) = Base64.getDecoder().decode(row.getString(key))
            val key = SecretKeySpec(bytes("vaultKey"), "AES"); val nonce = bytes("nonce")
            val random = object : SecureRandom() { override fun nextBytes(bytes: ByteArray) { nonce.copyInto(bytes) } }
            assertArrayEquals(bytes("encrypted"), AutomaticBackupCredentialCodec.seal(key, bytes("plaintext"), random))
            assertArrayEquals(bytes("plaintext"), AutomaticBackupCredentialCodec.open(key, bytes("encrypted")))
            AutomaticBackupCredentialCodec.parseConfiguration(bytes("plaintext")).use { config ->
                assertEquals("synthetic-backup-secret", config.password.concatToString())
                assertEquals(15, config.minutes); assertEquals(10, config.keep)
            }
        }
    }
}
