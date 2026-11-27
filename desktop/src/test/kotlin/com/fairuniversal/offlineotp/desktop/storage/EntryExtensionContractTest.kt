package com.fairuniversal.offlineotp.desktop.storage

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import com.fairuniversal.offlineotp.Database.VaultJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EntryExtensionContractTest {
    private fun fixture() = javaClass.getResourceAsStream("/golden/entry-extension-fields.json")!!.bufferedReader().use { it.readText() }
    private fun equivalent(expected: Any, actual: Any) {
        when (expected) {
            is JSONObject -> {
                val value = actual as JSONObject
                val keys = expected.keys().asSequence().toSet()
                assertEquals(keys, value.keys().asSequence().toSet())
                keys.forEach { equivalent(expected.get(it), value.get(it)) }
            }
            is JSONArray -> {
                val value = actual as JSONArray
                assertEquals(expected.length(), value.length())
                for (i in 0 until expected.length()) equivalent(expected.get(i), value.get(i))
            }
            else -> assertEquals(expected.toString(), actual.toString())
        }
    }
    @Test fun unknownNestedFieldsAndNullSurviveSaveReloadAndTypedEdits() {
        val expected = JSONArray(fixture()).getJSONObject(0)
        val entries = VaultJson.stringToEntries(fixture())
        try {
            val entry = entries.single()
            equivalent(expected, entry.toJSON())
            entry.setLabel("edited label")
            val edited = expected.put("label", "edited label")
            val reopened = VaultJson.stringToEntries(VaultJson.entriesToString(entries))
            try { equivalent(edited, reopened.single().toJSON()) }
            finally { reopened.forEach { it.destroy() } }
        } finally { entries.forEach { it.destroy() } }
    }
    @Test fun sourceAndReturnedJsonCannotMutateStoredExtensions() {
        val source = JSONArray(fixture()).getJSONObject(0)
        val entry = Entry(source)
        try {
            source.getJSONObject("extension_object").put("version", 99)
            entry.toJSON().getJSONObject("extension_object").put("version", 100)
            assertEquals(2, entry.toJSON().getJSONObject("extension_object").getInt("version"))
            assertTrue(entry.toJSON().has("extension_null"))
            entry.destroy()
            val field = Entry::class.java.getDeclaredField("extensionFields").apply { isAccessible = true }
            assertEquals(null, field.get(entry))
        } finally { entry.destroy() }
    }
    @Test fun directCyclicOrOverdeepMetadataIsRejectedBeforeSerialization() {
        for (cycle in listOf(false, true)) {
            val source = JSONArray(fixture()).getJSONObject(0)
            val objectValue = JSONObject()
            if (cycle) objectValue.put("self", objectValue)
            else {
                var next = objectValue
                repeat(40) { val child = JSONObject(); next.put("nested", child); next = child }
            }
            source.put("extension_object", objectValue)
            try { Entry(source).destroy(); fail("unsafe metadata accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun commonManualEditPreservesExtensionsAndTypedFieldsStayAuthoritative() {
        val source = Entry(JSONArray(fixture()).getJSONObject(0))
        try {
            val edited = ManualEntryInput.fromEntry(source).copy(label = "edited").build(source)
            try {
                assertEquals("edited", edited.getLabel())
                equivalent(source.toJSON().getJSONObject("extension_object"), edited.toJSON().getJSONObject("extension_object"))
                source.destroy()
                assertEquals(2, edited.toJSON().getJSONObject("extension_object").getInt("version"))
            } finally { edited.destroy() }
        } finally { source.destroy() }
    }

    @Test fun combinedExtensionSizeCannotProduceAnUnreopenableVault() {
        val source = JSONArray(fixture()).getJSONObject(0)
        val size = com.fairuniversal.offlineotp.Utilities.BoundedInputReader.MAX_DOCUMENT_BYTES / 2
        source.put("extension_large", "x".repeat(size))
        val entry = Entry(source)
        try {
            try { VaultJson.entriesToString(listOf(entry, entry)); fail("oversized vault was serialized") }
            catch (_: VaultJson.MalformedVaultException) { }
        } finally { entry.destroy() }
    }

    @Test fun synchronousReplacementPreservesExtensionsOnDiskNotJustInLiveState() {
        val folder = java.nio.file.Files.createTempDirectory("extension-replace-").toFile()
        val controller = com.fairuniversal.offlineotp.desktop.control.VaultController(DesktopStorage.open(folder))
        val loaded = controller.createVault("synthetic password") as com.fairuniversal.offlineotp.desktop.control.VaultController.Session.Loaded
        val state = com.fairuniversal.offlineotp.desktop.control.DesktopVaultState(loaded.key)
        val original = Entry(JSONArray(fixture()).getJSONObject(0))
        val replacement = ManualEntryInput(label = "edited", secret = "JBSWY3DPEHPK3PXP").build()
        try {
            state.add(original)
            assertTrue(com.fairuniversal.offlineotp.desktop.control.EntryEditor(controller, state).replace(original, replacement))
            val reopened = controller.openVault("synthetic password") as com.fairuniversal.offlineotp.desktop.control.VaultController.Session.Loaded
            try {
                assertEquals(2, reopened.entries.single().toJSON().getJSONObject("extension_object").getInt("version"))
                assertEquals("edited", reopened.entries.single().getLabel())
            } finally { com.fairuniversal.offlineotp.desktop.control.DesktopUnlockOperation.discard(reopened) }
        } finally { state.lock(); replacement.destroy(); original.destroy(); folder.deleteRecursively() }
    }

}
