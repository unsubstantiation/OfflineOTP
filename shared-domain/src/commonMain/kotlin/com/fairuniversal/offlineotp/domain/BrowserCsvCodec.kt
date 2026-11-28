package com.fairuniversal.offlineotp.domain


/** Parser for plaintext browser password exports. Callers must protect both input and results. */
object BrowserCsvCodec {
    // The vault and its Base32-encoded JSON must still fit the existing 16 MiB document limit.
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    const val MAX_FIELD_CHARS = 64 * 1024
    const val MAX_COLUMNS = 64
    const val MAX_ROWS = EntryDocumentCodec.MAX_ENTRIES

    enum class Browser { CHROME, FIREFOX, SAFARI }
    enum class Error { TOO_LARGE, INVALID_UTF8, INVALID_CSV, UNSUPPORTED_FORMAT, TOO_MANY_ROWS }

    sealed class Result {
        class Success(val browser: Browser, val columns: List<String>, val rows: List<Row>) : Result() {
            override fun toString(): String = "BrowserCsvCodec.Success(redacted)"
        }
        class Failure(val error: Error) : Result()
    }

    /** Includes every exported column, including optional notes, OTPAuth and browser metadata. */
    class Row internal constructor(private val values: List<String>) {
        fun valueAt(index: Int): String = values[index]
        val size: Int get() = values.size
        override fun toString(): String = "BrowserCsvCodec.Row(redacted)"
    }

    /** Parses a UTF-8 CSV file without modifying [bytes]; no errors include source contents. */
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
        } catch (invalid: CsvSyntaxRejected) {
            Result.Failure(Error.valueOf(invalid.error))
        }
    }

    /** RFC 4180 quoting, including embedded newlines and doubled quotes. */
    private class Rejected(val error: Error) : RuntimeException()
}
