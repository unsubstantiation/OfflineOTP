package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.domain.EntryMetadata
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PortableEntryMetadataContractTest {
    @Test fun adapterRoundTripPreservesMetadataExtensionsAndKindSpecificFields() {
        val fixture = javaClass.getResourceAsStream("/golden/portable-entry-metadata.json")!!.bufferedReader().use { it.readText() }
        val cases = JSONObject(fixture).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val source = cases.getJSONObject(index).getString("document")
            val expected = JSONArray(source).getJSONObject(0)
            val entries = VaultJson.stringToEntries(source)
            try {
                val encoded = VaultJson.entriesToString(entries)
                val reopened = VaultJson.stringToEntries(encoded)
                try {
                    val actual = JSONArray(VaultJson.entriesToString(reopened)).getJSONObject(0)
                    for (name in listOf("issuer", "label", "tags", "last_used", "used_frequency", "extension_object", "extension_list", "extension_null")) {
                        assertEquals(name, expected.get(name).toString(), actual.get(name).toString())
                    }
                    if (expected.has("counter")) assertEquals(expected.getLong("counter"), actual.getLong("counter"))
                    val model = EntryMetadata(entries[0].getIssuer(), entries[0].getLabel(), "", entries[0].getTags(),
                        entries[0].getLastUsed(), entries[0].getUsedFrequency(), false)
                    val patch = JSONObject(model.toJson())
                    assertEquals(expected.getLong("last_used"), patch.getLong("last_used"))
                    assertEquals(expected.getLong("used_frequency"), patch.getLong("used_frequency"))
                } finally { reopened.forEach { it.destroy() } }
            } finally { entries.forEach { it.destroy() } }
        }
    }
}
