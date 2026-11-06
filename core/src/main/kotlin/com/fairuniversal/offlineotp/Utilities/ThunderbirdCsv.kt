package com.fairuniversal.offlineotp.Utilities

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Parser for Thunderbird email credential exports.
 *
 * Thunderbird stores email logins in the same password-manager store Firefox uses: each
 * login has a hostname (the mail server URL, e.g. mailbox://, imap://, smtp://, pop://),
 * a username (the mailbox user / email address) and a password, plus the same optional
 * metadata Firefox exports (httpRealm, formActionOrigin, guid, timestamps). The on-disk
 * logins.json is encrypted with key4.db, but Thunderbird's "Export Logins" (and the legacy
 * Password Exporter add-on) write the same CSV Firefox does: url, username, password ….
 *
 * OfflineOTP keeps imported Thunderbird credentials as PASSWORD vault entries — the same
 * material PasswordImport uses — so they survive encrypted backups, tag/search/sort, and the
 * Passwords-tab details view, without needing a separate EMAIL-shaped account. The CSV
 * itself is the Thunderbird interchange: import reads url or hostname as the origin, export
 * writes the Firefox/Thunderbird canonical header.
 *
 * Callers must protect both input and results.
 */
object ThunderbirdCsv {
    const val MAX_FILE_BYTES = BrowserPasswordCsv.MAX_FILE_BYTES
    const val MAX_FIELD_CHARS = BrowserPasswordCsv.MAX_FIELD_CHARS
    const val MAX_COLUMNS = BrowserPasswordCsv.MAX_COLUMNS
    const val MAX_ROWS = BrowserPasswordCsv.MAX_ROWS

    enum class Error { TOO_LARGE, INVALID_UTF8, INVALID_CSV, UNSUPPORTED_FORMAT, TOO_MANY_ROWS }

    sealed class Result {
        class Success(val columns: List<String>, val rows: List<Row>) : Result() {
            override fun toString(): String = "ThunderbirdCsv.Success(redacted)"
        }
        class Failure(val error: Error) : Result()
    }

    class Row internal constructor(private val values: List<String>) {
        fun valueAt(index: Int): String = values[index]
        val size: Int get() = values.size
        override fun toString(): String = "ThunderbirdCsv.Row(redacted)"
    }

    /** CSV header we write for Thunderbird-compatible exports. */
    val EXPORT_COLUMNS = listOf(
        "url", "username", "password", "httpRealm", "formActionOrigin", "guid",
        "timeCreated", "timeLastUsed", "timePasswordChanged"
    )

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
            // Thunderbird accepts either url (Firefox export) or hostname (raw logins.json-style)
            val hasOrigin = "url" in names || "hostname" in names || "host" in names ||
                "login_uri" in names || "origin" in names
            val hasUser = "username" in names || "login_username" in names || "user" in names
            val hasPass = "password" in names || "login_password" in names || "pass" in names
            if (!hasOrigin || !hasUser || !hasPass) throw Rejected(Error.UNSUPPORTED_FORMAT)
            if (records.size == 1) throw Rejected(Error.INVALID_CSV)
            val rows = records.drop(1).map {
                if (it.size != columns.size) throw Rejected(Error.INVALID_CSV)
                Row(it.toList())
            }
            Result.Success(columns.toList(), rows)
        } catch (invalid: Rejected) {
            Result.Failure(invalid.error)
        }
    }

    fun exportCsv(columns: List<String>, rows: List<List<String>>): ByteArray {
        require(columns.isNotEmpty() && columns.size <= MAX_COLUMNS)
        rows.forEach { require(it.size == columns.size) }
        val out = StringBuilder()
        fun escape(value: String): String {
            require(value.length <= MAX_FIELD_CHARS)
            return if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else value
        }
        out.append(columns.joinToString(",") { escape(it) }).append("\n")
        rows.forEach { row ->
            out.append(row.joinToString(",") { escape(it) }).append("\n")
        }
        val bytes = out.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "Export too large" }
        return bytes
    }

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
