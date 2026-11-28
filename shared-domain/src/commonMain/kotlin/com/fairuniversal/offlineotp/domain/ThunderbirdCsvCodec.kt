package com.fairuniversal.offlineotp.domain


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
object ThunderbirdCsvCodec {
    const val MAX_FILE_BYTES = BrowserCsvCodec.MAX_FILE_BYTES
    const val MAX_FIELD_CHARS = BrowserCsvCodec.MAX_FIELD_CHARS
    const val MAX_COLUMNS = BrowserCsvCodec.MAX_COLUMNS
    const val MAX_ROWS = BrowserCsvCodec.MAX_ROWS

    enum class Error { TOO_LARGE, INVALID_UTF8, INVALID_CSV, UNSUPPORTED_FORMAT, TOO_MANY_ROWS }

    sealed class Result {
        class Success(val columns: List<String>, val rows: List<Row>) : Result() {
            override fun toString(): String = "ThunderbirdCsvCodec.Success(redacted)"
        }
        class Failure(val error: Error) : Result()
    }

    class Row internal constructor(private val values: List<String>) {
        fun valueAt(index: Int): String = values[index]
        val size: Int get() = values.size
        override fun toString(): String = "ThunderbirdCsvCodec.Row(redacted)"
    }

    /** CSV header we write for Thunderbird-compatible exports. */
    val EXPORT_COLUMNS = listOf(
        "url", "username", "password", "httpRealm", "formActionOrigin", "guid",
        "timeCreated", "timeLastUsed", "timePasswordChanged"
    )

    fun parse(bytes: ByteArray): Result {
        if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) return Result.Failure(Error.TOO_LARGE)
        val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
        catch (_: Exception) { return Result.Failure(Error.INVALID_UTF8) }
        return try {
            val records = CsvSyntax.readRecords(text.removePrefix("\uFEFF"))
            if (records.isEmpty()) throw Rejected(Error.INVALID_CSV)
            val columns = records.first()
            val normalized = columns.map { it.trim().lowercase() }
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
        } catch (invalid: CsvSyntaxRejected) {
            Result.Failure(Error.valueOf(invalid.error))
        }
    }

    fun exportCsv(columns: List<String>, rows: List<List<String>>): ByteArray {
        require(columns.isNotEmpty() && columns.size <= MAX_COLUMNS)
        require(rows.size <= MAX_ROWS)
        rows.forEach { require(it.size == columns.size) }
        var budget = 0L
        for (row in listOf(columns) + rows) {
            budget += row.size // delimiters plus final LF
            for (field in row) {
                require(field.length <= MAX_FIELD_CHARS)
                val quoted = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
                budget += EntryDocumentCodec.utf8Size(field) + if (quoted) field.count { it == '"' } + 2L else 0L
                require(budget <= MAX_FILE_BYTES) { "Export too large" }
            }
        }
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
        val bytes = out.toString().encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.size <= MAX_FILE_BYTES) { "Export too large" }
        return bytes
    }

    private class Rejected(val error: Error) : RuntimeException()
}
