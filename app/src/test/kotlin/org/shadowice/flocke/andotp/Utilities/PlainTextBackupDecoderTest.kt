/*
 * Import-only plain-text backup decoding checks. The UI warns before opening a file and the
 * existing all-or-nothing vault parser validates the JSON after this strict UTF-8 boundary.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class PlainTextBackupDecoderTest {
    @Test
    fun decodesUtf8AndStripsAnOptionalBom() {
        val json = "[{\"secret\":\"JBSWY3DPEHPK3PXP\",\"label\":\"alice\"}]"
        assertEquals(json, PlainTextBackupDecoder.decode(json.toByteArray(StandardCharsets.UTF_8)))
        assertEquals(json, PlainTextBackupDecoder.decode(
            ("\uFEFF" + json).toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun rejectsEmptyAndWhitespaceOnlyDocuments() {
        for (data in listOf(ByteArray(0), " \n\t".toByteArray(StandardCharsets.UTF_8))) {
            assertInvalid(data)
        }
        assertInvalid(null)
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfReplacingSecretBytes() {
        assertInvalid(byteArrayOf(0x5b, 0x5d, 0xc3.toByte(), 0x28))
    }

    @Test
    fun enforcesTheDocumentSizeLimit() {
        assertInvalid(ByteArray(BoundedInputReader.MAX_DOCUMENT_BYTES + 1))
    }

    private fun assertInvalid(data: ByteArray?) {
        try {
            PlainTextBackupDecoder.decode(data)
            throw AssertionError("invalid plain-text backup was accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
