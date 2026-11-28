package com.fairuniversal.offlineotp.domain

internal class CsvSyntaxRejected(val error: String) : IllegalArgumentException()
internal object CsvSyntax {
    private const val MAX_FIELD_CHARS = 65536
    private const val MAX_COLUMNS = 64
    private const val MAX_ROWS = EntryDocumentCodec.MAX_ENTRIES
    fun readRecords(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var fieldStarted = false
        var recordStarted = false
        var i = 0

        fun append(c: Char) {
            if (field.length >= MAX_FIELD_CHARS) throw CsvSyntaxRejected("TOO_LARGE")
            field.append(c)
        }
        fun endField() {
            if (fields.size >= MAX_COLUMNS) throw CsvSyntaxRejected("TOO_LARGE")
            fields.add(field.toString())
            field.clear()
            fieldStarted = false
            closedQuote = false
        }
        fun endRecord() {
            endField()
            if (records.size >= MAX_ROWS + 1) throw CsvSyntaxRejected("TOO_MANY_ROWS")
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
                    if (fieldStarted || closedQuote) throw CsvSyntaxRejected("INVALID_CSV")
                    quoted = true; fieldStarted = true; recordStarted = true
                }
                ',' -> { endField(); recordStarted = true }
                '\r', '\n' -> {
                    if (c == '\r' && i < text.length && text[i] == '\n') i++
                    if (!recordStarted && fields.isEmpty() && !fieldStarted && !closedQuote)
                        throw CsvSyntaxRejected("INVALID_CSV")
                    endRecord()
                }
                else -> {
                    if (closedQuote) throw CsvSyntaxRejected("INVALID_CSV")
                    append(c); fieldStarted = true; recordStarted = true
                }
            }
        }
        if (quoted) throw CsvSyntaxRejected("INVALID_CSV")
        if (recordStarted || fields.isNotEmpty() || fieldStarted || closedQuote) endRecord()
        return records
    }

}
