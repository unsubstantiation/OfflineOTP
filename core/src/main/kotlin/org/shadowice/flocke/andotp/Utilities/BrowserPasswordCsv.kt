package org.shadowice.flocke.andotp.Utilities

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Parser for plaintext browser password exports. Callers must protect both input and results. */
object BrowserPasswordCsv {
    // The vault and its Base32-encoded JSON must still fit the existing 16 MiB document limit.
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    const val MAX_FIELD_CHARS = 64 * 1024
    const val MAX_COLUMNS = 64
    const val MAX_ROWS = InputPolicy.MAX_ENTRIES

    enum class Browser { CHROME, FIREFOX, SAFARI }
    enum class Error { TOO_LARGE, INVALID_UTF8, INVALID_CSV, UNSUPPORTED_FORMAT, TOO_MANY_ROWS }

    sealed class Result {
        class Success(val browser: Browser, val columns: List<String>, val rows: List<Row>) : Result() {
            override fun toString(): String = "BrowserPasswordCsv.Success(redacted)"
        }
        class Failure(val error: Error) : Result()
    }

    /** Includes every exported column, including optional notes, OTPAuth and browser metadata. */
    class Row internal constructor(private val values: List<String>) {
        fun valueAt(index: Int): String = values[index]
        val size: Int get() = values.size
        override fun toString(): String = "BrowserPasswordCsv.Row(redacted)"
    }

    /** Parses a UTF-8 CSV file without modifying [bytes]; no errors include source contents. */
    @JvmStatic
    fun parse(bytes: ByteArray): Result {
        if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) return Result.Failure(Error.TOO_LARGE)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return Result.Failure(Error.INVALID_UTF8)
        }
        return try {
            val records = readRecords(text.removePrefix("\uFEFF"))
            if (records.isEmpty()) throw Rejected(Error.INVALID_CSV)
            val columns = records.first()
            val normalized = columns.map { it.trim().lowercase(Locale.ROOT) }
            if (normalized.any { it.isEmpty() } || normalized.toSet().size != normalized.size)
                throw Rejected(Error.INVALID_CSV)
            val names = normalized.toSet()
            val required = setOf("url", "username", "password")
            val browser = when {
                !names.containsAll(required) -> throw Rejected(Error.UNSUPPORTED_FORMAT)
                "title" in names -> Browser.SAFARI
                "name" in names -> Browser.CHROME
                "httprealm" in names || "formactionorigin" in names || "guid" in names -> Browser.FIREFOX
                else -> throw Rejected(Error.UNSUPPORTED_FORMAT)
            }
            if (records.size == 1) throw Rejected(Error.INVALID_CSV)
            val rows = records.drop(1).map {
                if (it.size != columns.size) throw Rejected(Error.INVALID_CSV)
                Row(it.toList())
            }
            Result.Success(browser, columns.toList(), rows)
        } catch (invalid: Rejected) {
            Result.Failure(invalid.error)
        }
    }

    /** RFC 4180 quoting, including embedded newlines and doubled quotes. */
    private fun readRecords(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var fieldStarted = false
        var recordStarted = false
        var i = 0

        fun append(c: Char) {
            if (field.length >= MAX_FIELD_CHARS) throw Rejected(Error.TOO_LARGE)
            field.append(c)
        }
        fun endField() {
            if (fields.size >= MAX_COLUMNS) throw Rejected(Error.TOO_LARGE)
            fields.add(field.toString())
            field.setLength(0)
            fieldStarted = false
            closedQuote = false
        }
        fun endRecord() {
            endField()
            if (records.size >= MAX_ROWS + 1) throw Rejected(Error.TOO_MANY_ROWS)
            records.add(fields)
            fields = ArrayList()
            recordStarted = false
        }
        while (i < text.length) {
            val c = text[i++]
            if (quoted) {
                if (c == '"') {
                    if (i < text.length && text[i] == '"') { append('"'); i++ }
                    else { quoted = false; closedQuote = true }
                } else append(c)
                continue
            }
            when (c) {
                '"' -> {
                    if (fieldStarted || closedQuote) throw Rejected(Error.INVALID_CSV)
                    quoted = true; fieldStarted = true; recordStarted = true
                }
                ',' -> { endField(); recordStarted = true }
                '\r', '\n' -> {
                    if (c == '\r' && i < text.length && text[i] == '\n') i++
                    if (!recordStarted && fields.isEmpty() && !fieldStarted && !closedQuote)
                        throw Rejected(Error.INVALID_CSV)
                    endRecord()
                }
                else -> {
                    if (closedQuote) throw Rejected(Error.INVALID_CSV)
                    append(c); fieldStarted = true; recordStarted = true
                }
            }
        }
        if (quoted) throw Rejected(Error.INVALID_CSV)
        if (recordStarted || fields.isNotEmpty() || fieldStarted || closedQuote) endRecord()
        return records
    }

    private class Rejected(val error: Error) : RuntimeException()
}
