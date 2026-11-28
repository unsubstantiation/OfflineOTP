package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupCodec
import com.fairuniversal.offlineotp.Utilities.VaultContainer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullBackupRestoreContractTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun rejects(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
    }
    private fun resource(name: String) = javaClass.getResourceAsStream("/golden/$name")!!.readBytes()

    @Test fun allThreeAndOtpEncodingsMatchIndependentVectorsAndKeepCountersAndTags() {
        val rows = JSONObject(String(resource("full-backup-restore.json"))).getJSONArray("vectors")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val bytes = hex(row.getString("dataHex"))
            val variant = row.getString("variant"); val password = row.getString("password")
            val text = EncryptedBackupCodec.openSelected(password, bytes, "andotp", variant)
            assertEquals(row.getString("plaintext"), text)
            val entries = VaultJson.stringToEntries(text)
            try {
                assertEquals(2, entries.size); assertEquals(listOf("work", "test"), entries[0].getTags())
                assertEquals(9L, entries[1].getCounter()); assertEquals(8, entries[1].getDigits())
            } finally { entries.forEach { it.destroy() } }
            if (variant != "plain") {
                rejects { EncryptedBackupCodec.openSelected("wrong", bytes, "andotp", variant) }
                for (offset in listOf(0, bytes.size / 2, bytes.lastIndex)) {
                    val changed = bytes.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
                    rejects { EncryptedBackupCodec.openSelected(password, changed, "andotp", variant) }
                }
            }
            rejects { EncryptedBackupCodec.openSelected(password, bytes, "vault-copy", variant) }
        }
    }

    @Test fun explicitFormatDoesNotFallbackAndOfflineOtpAcceptsBothHistoricalExportKdfs() {
        val rows = JSONObject(String(resource("vault-container.json"))).getJSONArray("vectors")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val bytes = hex(row.getString("fileHex")); val password = row.getString("password")
            assertEquals(row.getString("plaintext"), EncryptedBackupCodec.openSelected(password, bytes, "vault-copy"))
            rejects { EncryptedBackupCodec.openSelected(password, bytes, "offlineotp") }
            for (variant in listOf("encrypted", "plain", "pre063")) rejects { EncryptedBackupCodec.openSelected(password, bytes, "andotp", variant) }
        }
        for (name in listOf("otp_accounts.json.aes", "desktop-export.json.aes")) {
            val bytes = resource(name)
            assertEquals(String(resource("expected-vault.json")), EncryptedBackupCodec.openSelected("golden-interop-password", bytes, "offlineotp"))
            rejects { EncryptedBackupCodec.openSelected("golden-interop-password", bytes, "vault-copy") }
        }
        rejects { EncryptedBackupCodec.openSelected("password", byteArrayOf(1), "unknown") }
        rejects { EncryptedBackupCodec.openSelected("", "[{},{}]".toByteArray(), "andotp", "plain") }
        rejects { EncryptedBackupCodec.openSelected("", "[] trailing".toByteArray(), "andotp", "plain") }
        rejects { EncryptedBackupCodec.openSelected("", byteArrayOf(0xc0.toByte(), 0xaf.toByte()), "andotp", "plain") }
        assertEquals("[]", EncryptedBackupCodec.openSelected("", "[]".toByteArray(), "andotp", "plain"))
        rejects { EncryptedBackupCodec.openSelected("", "[]".toByteArray(), "andotp", "guess") }
    }
}
