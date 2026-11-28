package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.domain.*
import com.fairuniversal.offlineotp.Utilities.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

class PortableVaultProtocolContractTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun corpus() = javaClass.getResourceAsStream("/golden/portable-vault-protocol.json")!!.bufferedReader().use { JSONObject(it.readText()) }
    @Test fun independentFramingCorpusMatchesSharedPolicyAndJvmAdapters() {
        val policy = VaultProtocol(); val rows = corpus().getJSONArray("headers")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val input = hex(row.getString("prefixHex")); val total = row.getInt("totalBytes")
            val vault = row.getString("family") == "vault"
            val result = if (vault) policy.vaultHeader(input, total) else policy.backupHeader(input, total)
            if (row.optString("error", null) != null) {
                assertNull(result.header); assertEquals(row.getString("error"), result.failure!!.name)
                // Avoid allocating the oversized corpus case: policy already checked actual size.
                if (total in 0..100 && input.size >= (if (vault) 40 else 30)) {
                    val full = input.copyOf(total)
                    try { if (vault) VaultContainer.header(full) else EncryptedBackupHeader.parse(full); fail("Accepted " + row.getString("name")) }
                    catch (_: IllegalArgumentException) { }
                }
            } else {
                val header = result.header!!
                assertNull(result.failure); assertEquals(row.getString("kdf"), header.kdf.name)
                assertEquals(row.getInt("memory"), header.memoryKiB); assertEquals(row.getInt("iterations"), header.iterations)
                assertEquals(row.getInt("lanes"), header.parallelism); assertTrue(hex(row.getString("saltHex")).contentEquals(header.salt()))
                assertEquals(row.getInt("offset"), header.encryptedOffset); assertEquals(row.getInt("keyBytes"), header.keyBytes)
                assertEquals(row.getInt("derivedBytes"), header.derivedBytes)
                val full = input.copyOf(total)
                if (vault) {
                    val jvm = VaultContainer.header(full)
                    assertEquals(header.iterations, jvm.iterations); assertTrue(jvm.salt.contentEquals(header.salt()))
                    assertEquals(jvm.iterations, VaultContainer.fromParameters(jvm.kdf, jvm.salt, jvm.memoryKiB, jvm.iterations, jvm.parallelism).iterations)
                } else {
                    val jvm = EncryptedBackupHeader.parse(full)
                    assertEquals(header.iterations, jvm.getIterations()); assertTrue(header.salt().contentEquals(jvm.getSalt()))
                    if (header.kdf == ProtocolKdf.ARGON2ID) assertTrue(full.contentEquals(EncryptedBackupHeader.frameArgon2id(header.memoryKiB, header.iterations, header.parallelism, header.salt(), jvm.getEncrypted())))
                }
            }
        }
    }
    @Test fun explicitAndLegacyAutomaticDispatchMatchIndependentCorpus() {
        val rows = corpus().getJSONArray("routes"); val policy = VaultProtocol()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val result = policy.select(hex(row.getString("prefixHex")), row.getInt("totalBytes"), row.getString("format"), row.getString("variant"))
            if (row.optString("route", null) == null) { assertNull(result.route); assertNotNull(result.failure) }
            else { assertEquals(row.getString("name"), row.getString("route"), result.route?.name); assertNull(result.failure) }
        }
    }
}
