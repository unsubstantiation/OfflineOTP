package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.domain.EntryDocumentCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableEntryDocumentContractTest {
    private fun corpus(): JSONObject = javaClass.getResourceAsStream("/golden/portable-entry-documents.json")!!.bufferedReader().use { JSONObject(it.readText()) }

    @Test fun everyKindSurvivesSharedAndNativeDecodeEditSaveAndReload() {
        val rows = corpus().getJSONArray("valid")
        assertEquals(9, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val source = row.getString("document")
            val original = JSONArray(source).getJSONObject(0)
            val shared = EntryDocumentCodec().decode(source).document!!
            try {
                assertEquals(row.getString("kind"), shared.entries.single().parameters.kind.name)
                assertEquals(source, shared.json())
            } finally { shared.destroy() }
            val entries = VaultJson.stringToEntries(source)
            try {
                entries.single().setLabel("edited " + row.getString("kind"))
                val stored = VaultJson.entriesToString(entries)
                val restored = VaultJson.stringToEntries(stored)
                try {
                    assertEquals(row.getString("kind"), restored.single().getType().name)
                    assertEquals(entries.single().getLabel(), restored.single().getLabel())
                    val result = JSONArray(VaultJson.entriesToString(restored)).getJSONObject(0)
                    for (name in listOf("secret", "extension_object", "extension_null", "last_used", "used_frequency", "tags", "hidden_label", "details_visible")) {
                        assertEquals(row.getString("kind") + ":" + name, original.get(name).toString(), result.get(name).toString())
                    }
                    if (original.has("counter")) assertEquals(original.getLong("counter"), result.getLong("counter"))
                    if (original.has("pin")) assertEquals(original.getString("pin"), result.getString("pin"))
                    if (original.has("ssh_password")) assertEquals(original.getString("ssh_password"), result.getString("ssh_password"))
                } finally { restored.forEach { it.destroy() } }
            } finally { entries.forEach { it.destroy() } }
        }
    }

    @Test fun sharedAndNativeAdaptersBothRejectEveryInvalidDocumentWithoutPartialEntries() {
        val rows = corpus().getJSONArray("invalid")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val source = row.getString("document")
            assertEquals(row.getString("name"), null, EntryDocumentCodec().roundTrip(source))
            var failed = false
            try { VaultJson.stringToEntries(source).forEach { it.destroy() } } catch (_: Exception) { failed = true }
            assertTrue(row.getString("name"), failed)
        }
    }

    @Test fun opaqueNumbersStayLosslessAcrossNativeCopiesEditsAndPersistence() {
        val text = """[{"secret":"JBSWY3DPEHPK3PXP","label":"synthetic","future":{"huge":123456789012345678901234567890,"decimal":1.2300e-15,"wide":1e9999}}]"""
        val source = VaultJson.stringToEntries(text)
        val copy = source.single().detachedCopy()
        try {
            copy.setLabel("edited")
            val output = VaultJson.entriesToString(listOf(copy))
            for (token in listOf("123456789012345678901234567890", "1.2300e-15", "1e9999")) assertTrue(output.contains(token))
            val reloaded = VaultJson.stringToEntries(output)
            try { assertEquals(output, VaultJson.entriesToString(reloaded)) }
            finally { reloaded.forEach { it.destroy() } }
        } finally { source.forEach { it.destroy() }; copy.destroy() }
    }

    @Test fun passwordAndEmailMaterialEditorsPreserveOpaqueFutureFields() {
        val rows = corpus().getJSONArray("valid")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val kind = row.getString("kind")
            if (kind != "PASSWORD" && kind != "EMAIL") continue
            val source = row.getString("document").dropLast(2) + ",\"future\":{\"wide\":1e9999}}]"
            val entries = VaultJson.stringToEntries(source)
            val replacement = if (kind == "PASSWORD") com.fairuniversal.offlineotp.Utilities.PasswordCsvExport.withEditedFields(entries.single(), password = "changed synthetic")
                else com.fairuniversal.offlineotp.ui.vault.EmailPasswordInput(incoming = "changed synthetic", outgoing = listOf("")).build(entries.single())
            try { assertTrue(VaultJson.entriesToString(listOf(replacement)).contains("1e9999")) }
            finally { replacement.destroy(); entries.forEach { it.destroy() } }
        }
    }

    @Test fun nativeMaterialValidationIsNotReplacedByAStructuralModel() {
        // Valid Base32 bytes, but not a PWD1 password payload. Shared syntax alone must not install it.
        val text = """[{"type":"PASSWORD","secret":"JBSWY3DPEHPK3PXP","label":"synthetic"}]"""
        val model = EntryDocumentCodec().decode(text).document!!
        model.destroy()
        var failed = false
        try { VaultJson.stringToEntries(text) } catch (_: Exception) { failed = true }
        assertTrue(failed)
    }
}
