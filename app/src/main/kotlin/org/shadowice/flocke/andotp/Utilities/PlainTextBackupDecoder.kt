package org.shadowice.flocke.andotp.Utilities

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Strict, bounded UTF-8 decoding for the import-only legacy plain-text backup format. */
object PlainTextBackupDecoder {
    /**
     * Decodes a selected document without silently replacing invalid byte sequences. A leading
     * UTF-8 BOM is accepted for backups produced by general-purpose text editors. Plain-text
     * export remains intentionally unsupported.
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    fun decode(data: ByteArray?): String {
        if (data == null || data.isEmpty() || data.size > BoundedInputReader.MAX_DOCUMENT_BYTES)
            throw IllegalArgumentException("Invalid plain-text backup size")
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (invalid: CharacterCodingException) {
            throw IllegalArgumentException("Invalid UTF-8 plain-text backup", invalid)
        }
        val text = if (decoded.startsWith('\uFEFF')) decoded.substring(1) else decoded
        if (text.isBlank()) throw IllegalArgumentException("Empty plain-text backup")
        return text
    }
}
