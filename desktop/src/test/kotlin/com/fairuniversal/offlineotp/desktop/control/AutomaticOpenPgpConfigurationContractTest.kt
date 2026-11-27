package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.AutomaticBackupCredentialCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticOpenPgpConfigurationContractTest {
    @Test fun sharedConfigurationReadsAndWritesWithoutPasswordsPathsOrMutableAliases() {
        val fixture = javaClass.getResourceAsStream("/golden/automatic-openpgp-configuration.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val document = fixture.getJSONObject("configuration").toString().toByteArray()
        AutomaticBackupCredentialCodec.parseConfiguration(document).use { config ->
            assertEquals("/synthetic/automatic-backups", config.folder); assertEquals(15, config.minutes); assertEquals(3, config.keep)
            assertTrue(config.password.isEmpty())
            assertEquals(listOf("public-one", "public-two"), config.recipientKeys.map { String(it) })
            val written = AutomaticBackupCredentialCodec.recipientConfigurationDocument(config.folder, config.recipientKeys, config.minutes, config.keep)
            AutomaticBackupCredentialCodec.parseConfiguration(written).use { copy ->
                config.close()
                assertEquals(listOf("public-one", "public-two"), copy.recipientKeys.map { String(it) })
            }
        }
    }
    @Test fun corruptedModeTypesNumbersAndRecipientSetsAreRejectedRatherThanCoerced() {
        val base = """{"version":2,"mode":"openpgp","folder":"/synthetic","password":"","minutes":15,"keep":3,"recipientKeys":["AQ=="]}"""
        val invalid = listOf(
            base.replace("\"version\":2", "\"version\":2.5"),
            base.replace("\"minutes\":15", "\"minutes\":4294967311"),
            base.replace("\"keep\":3", "\"keep\":\"3\""),
            base.replace("\"folder\":\"/synthetic\"", "\"folder\":false"),
            base.replace("\"openpgp\"", "\"plaintext\""),
            base.replace("[\"AQ==\"]", "[]"),
            base.replace("[\"AQ==\"]", "[\"AQ==\",\"AQ==\"]"),
            base.replace("[\"AQ==\"]", "[\"not base64?\"]")
        )
        for (document in invalid) {
            var rejected = false
            try { AutomaticBackupCredentialCodec.parseConfiguration(document.toByteArray()).close() } catch (_: Exception) { rejected = true }
            assertTrue("invalid configuration accepted", rejected)
        }
    }
}
