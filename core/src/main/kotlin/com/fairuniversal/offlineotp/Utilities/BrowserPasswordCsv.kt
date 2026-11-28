package com.fairuniversal.offlineotp.Utilities


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

    @JvmStatic
    fun parse(bytes: ByteArray): Result = when (val parsed = com.fairuniversal.offlineotp.domain.BrowserCsvCodec.parse(bytes)) {
        is com.fairuniversal.offlineotp.domain.BrowserCsvCodec.Result.Failure -> Result.Failure(Error.valueOf(parsed.error.name))
        is com.fairuniversal.offlineotp.domain.BrowserCsvCodec.Result.Success -> Result.Success(Browser.valueOf(parsed.browser.name), parsed.columns,
            parsed.rows.map { row -> Row((0 until row.size).map { row.valueAt(it) }) })
    }
}
