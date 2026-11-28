package com.fairuniversal.offlineotp.Utilities


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
    fun parse(bytes: ByteArray): Result = when (val parsed = com.fairuniversal.offlineotp.domain.ThunderbirdCsvCodec.parse(bytes)) {
        is com.fairuniversal.offlineotp.domain.ThunderbirdCsvCodec.Result.Failure -> Result.Failure(Error.valueOf(parsed.error.name))
        is com.fairuniversal.offlineotp.domain.ThunderbirdCsvCodec.Result.Success -> Result.Success(parsed.columns,
            parsed.rows.map { row -> Row((0 until row.size).map { row.valueAt(it) }) })
    }
    fun exportCsv(columns: List<String>, rows: List<List<String>>): ByteArray =
        com.fairuniversal.offlineotp.domain.ThunderbirdCsvCodec.exportCsv(columns, rows)
}
