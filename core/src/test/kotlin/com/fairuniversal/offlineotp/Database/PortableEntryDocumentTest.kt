package com.fairuniversal.offlineotp.Database

import com.fairuniversal.offlineotp.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableEntryDocumentTest {
    private val codec = EntryDocumentCodec()
    private val entry = """{"secret":"JBSWY3DPEHPK3PXP","label":"synthetic"}"""
    private fun document(extra: String = "") = "[" + entry.dropLast(1) + extra + "}]"
    private fun rejects(text: String) { assertTrue(codec.decode(text).document == null) }

    @Test fun fullFieldBagAndTypedParametersPreserveUnknownValuesWithoutFloatConversion() {
        val source = document(""", "type":"HOTP", "counter":9007199254740993,"digits":8,"algorithm":"SHA512","future":{"n":123456789012345678901234567890,"fraction":1.2300e-15,"nil":null,"array":[true,"x"]}""")
        val decoded = codec.decode(source).document!!
        try {
            val record = decoded.entries.single()
            assertEquals(EntryRecordKind.HOTP, record.parameters.kind)
            assertEquals(EntryRecordAlgorithm.SHA512, record.parameters.algorithm)
            assertEquals(9007199254740993L, record.parameters.counter)
            val future = record.field("future")!!.json()
            assertTrue(future.contains("123456789012345678901234567890"))
            assertTrue(future.contains("1.2300e-15"))
            val edited = record.replacing("label", EntryText("edited"))
            try { assertEquals(future, edited.field("future")!!.json()) } finally { edited.destroy() }
            val again = codec.decode(decoded.json()!!).document!!
            try { assertEquals(decoded.json(), again.json()) } finally { again.destroy() }
            assertEquals("EntryRecord(redacted)", record.toString())
            assertEquals("EntryValue(redacted)", record.field("secret").toString())
        } finally { decoded.destroy() }
        assertEquals(null, decoded.json())
        assertTrue(decoded.entries.isEmpty())
    }

    @Test fun allKindsHavePortableDiscriminantsAndHistoricalDefaults() {
        for (kind in EntryRecordKind.entries) {
            val extra = ",\"type\":\"${kind.name}\"" + if (kind == EntryRecordKind.HOTP) ",\"counter\":0" else ""
            val doc = codec.decode(document(extra)).document!!
            try {
                val parameters = doc.entries.single().parameters
                assertEquals(kind, parameters.kind)
                assertEquals(EntryRecordAlgorithm.SHA1, parameters.algorithm)
                assertEquals(if (kind == EntryRecordKind.STEAM) 5 else 6, parameters.digits)
                assertEquals(30, parameters.period)
            } finally { doc.destroy() }
        }
    }

    @Test fun typedParametersRejectOverflowFractionsInvalidEnumsAndCounterWrap() {
        for (extra in listOf(",\"period\":0", ",\"period\":86401", ",\"digits\":0", ",\"digits\":10",
            ",\"digits\":6.0", ",\"counter\":-1", ",\"counter\":9223372036854775807",
            ",\"counter\":9223372036854775808", ",\"type\":\"FUTURE\"", ",\"algorithm\":\"MD5\"",
            ",\"type\":\"HOTP\"", ",\"type\":\"STEAM\",\"digits\":6")) rejects(document(extra))
        assertTrue(codec.decode(document(",\"type\":\"MOTP\",\"period\":\"ignored\"")).document != null)
    }

    @Test fun transferUsesSharedKnownFieldInterpretationButDoesNotPromoteExtensions() {
        val source = document(""", "type":"HOTP","counter":"9007199254740993","digits":"8","future":"9007199254740993","last_used":"123","details_visible":"true"""")
        val doc = codec.decode(source).document!!
        try {
            assertTrue(doc.json()!!.contains("\"counter\":\"9007199254740993\""))
            val transfer = doc.transferJson()!!
            assertTrue(transfer.contains("\"counter\":9007199254740993"))
            assertTrue(transfer.contains("\"future\":\"9007199254740993\""))
            assertTrue(transfer.contains("\"details_visible\":false"))
        } finally { doc.destroy() }
    }

    @Test fun strictSyntaxRejectsAmbiguousOrPartialDocumentsAndMalformedUnicode() {
        for (text in listOf("", "{}", "null", "[] []", "[true]", "[{}]", "[$entry,]", "[/*comment*/$entry]",
            "[{'secret':'JBSWY3DPEHPK3PXP','label':'x'}]", "[${entry.dropLast(1)},\"label\":\"duplicate\"}]",
            document(",\"x\":01"), document(",\"x\":+1"), document(",\"x\":1."), document(",\"x\":1e"),
            document(",\"x\":NaN"), document(",\"x\":\"\\uD800\""), document(",\"x\":\"\\uDC00\""),
            document(",\"x\":\"\\uGGGG\""), document(",\"x\":\"\\q\""), document(",\"x\":\"\u0000\""))) rejects(text)
        val text = document(",\"x\":\"\\uD83D\\uDE00\\n\\t\\b\\f\\r\\\\\\\"\\/\"")
        assertTrue(codec.roundTrip(text) != null)
    }

    @Test fun boundsAreAllOrNothingAndIncludeUtf8DepthEntryAndNodeLimits() {
        assertTrue(codec.roundTrip("[" + List(2000) { entry }.joinToString(",") + "]") != null)
        rejects("[" + List(2001) { entry }.joinToString(",") + "]")
        rejects(document(",\"future\":" + "[".repeat(33) + "0" + "]".repeat(33)))
        rejects(document(",\"future\":[" + List(EntryDocumentCodec.MAX_VALUES) { "null" }.joinToString(",") + "]"))
        rejects("[" + entry + ",{\"label\":\"no secret\"}]")
        rejects(" ".repeat(EntryDocumentCodec.MAX_BYTES + 1))
        rejects(document(",\"future\":\"" + "😀".repeat(EntryDocumentCodec.MAX_BYTES / 4 + 1) + "\""))
    }

    @Test fun base32AndMetadataAreValidatedBeforeNativeMaterialHandling() {
        for (secret in listOf("", "!!!", "A", "MZ======", "MY=", "MY=======", "MY======X")) {
            rejects("[{\"secret\":\"$secret\",\"label\":\"x\"}]")
        }
        assertTrue(codec.roundTrip("[{\"secret\":\"my======\",\"label\":\"x\"}]") != null)
        rejects(document(",\"tags\":[false]"))
        rejects(document(",\"issuer\":null"))
        rejects(document(",\"type\":\"SSH_KEY\",\"ssh_port\":0"))
    }

    @Test fun deterministicNestedExtensionCorpusRoundTripsWithoutChangingJsonValues() {
        for (i in 0 until 250) {
            val extension = EntryObject(mapOf("unicode😀$i" to EntryArray(listOf(EntryText("quote\"\\\n$i"), EntryBoolean(i % 2 == 0), EntryNull))))
            val source = document(",\"future\":" + extension.json())
            val doc = codec.decode(source).document!!
            try { assertEquals(extension.json(), doc.entries.single().field("future")!!.json()) }
            finally { doc.destroy() }
        }
    }
}
