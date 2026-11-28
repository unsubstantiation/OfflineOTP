package com.fairuniversal.offlineotp.Database

import com.fairuniversal.offlineotp.domain.EntryMetadata
import com.fairuniversal.offlineotp.domain.EntryMetadataError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableEntryMetadataTest {
    private fun metadata(issuer: String = "Issuer", tags: List<String> = listOf("same", "same")) =
        EntryMetadata(issuer, "Label", "hidden", tags, Long.MAX_VALUE, Long.MIN_VALUE, true)

    @Test fun metadataRetainsExactNumbersTagsAndEscapesWithoutLeakingInToString() {
        val model = metadata("quotes\" \\ </script>\n\t\u0000 日本語 😀 e\u0301")
        val encoded = model.toJson()
        assertTrue(encoded.contains("9223372036854775807"))
        assertTrue(encoded.contains("-9223372036854775808"))
        val decoded = JSONObject(encoded)
        assertEquals(model.issuer, decoded.getString("issuer"))
        assertEquals(model.lastUsed, decoded.getLong("last_used"))
        assertEquals(model.usedFrequency, decoded.getLong("used_frequency"))
        assertEquals(2, decoded.getJSONArray("tags").length())
        assertEquals("EntryMetadata(redacted)", model.toString())
        assertTrue(!model.toString().contains("hidden"))
    }

    @Test fun metadataOwnsCollectionsAndPreservesOrderAndDuplicates() {
        val tags = arrayListOf("first", "second", "first")
        val model = metadata(tags = tags)
        tags.clear()
        assertEquals(listOf("first", "second", "first"), model.tags)
        val snapshot = model.tags
        try { (snapshot as MutableList<String>).clear() } catch (_: Exception) { }
        assertEquals(listOf("first", "second", "first"), model.tags)
    }

    @Test fun sharedBoundsMatchTheLegacyContractInUtf16Units() {
        assertEquals(null, metadata("x".repeat(1024), List(32) { "x".repeat(128) }).validationError())
        assertEquals(EntryMetadataError.TEXT_TOO_LONG, metadata("x".repeat(1025)).validationError())
        assertEquals(EntryMetadataError.TOO_MANY_TAGS, metadata(tags = List(33) { "" }).validationError())
        assertEquals(EntryMetadataError.TEXT_TOO_LONG, metadata(tags = listOf("x".repeat(129))).validationError())
        assertEquals(null, metadata("😀".repeat(512)).validationError())
        assertEquals(EntryMetadataError.TEXT_TOO_LONG, metadata("😀".repeat(513)).validationError())
        assertEquals(EntryMetadata.MAX_TEXT_CHARS, com.fairuniversal.offlineotp.Utilities.InputPolicy.MAX_TEXT_CHARS)
    }

    @Test fun optionalDefaultFieldsStayOmittedAndInvalidMetadataIsNotSerialized() {
        val text = EntryMetadata("", "", "", emptyList(), 0, 0, false).toJson()
        val json = JSONObject(text)
        assertTrue(!json.has("hidden_label")); assertTrue(!json.has("details_visible"))
        assertEquals("", json.getString("issuer")); assertEquals(0, json.getJSONArray("tags").length())
        var rejected = false
        try { metadata("x".repeat(1025)).toJson() } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
}
