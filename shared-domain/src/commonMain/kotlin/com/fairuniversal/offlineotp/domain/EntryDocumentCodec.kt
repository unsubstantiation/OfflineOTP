package com.fairuniversal.offlineotp.domain

enum class EntryDocumentError { TOO_LARGE, INVALID_JSON, INVALID_ENTRY, TOO_MANY_ENTRIES }

/** ObjC/Swift-safe result: no parser exceptions or document text cross an error boundary. */
class EntryDocumentResult internal constructor(val document: EntryDocument?, val error: EntryDocumentError?) {
    override fun toString() = "EntryDocumentResult(redacted)"
}
class EntryDocument internal constructor(records: List<EntryRecord>) {
    private var owned: List<EntryRecord>? = records.toList()
    val entries: List<EntryRecord> get() = owned?.toList() ?: emptyList()
    fun json(): String? = render(false)
    fun transferJson(): String? = render(true)
    private fun render(transfer: Boolean): String? {
        val records = owned ?: return null
        val output = StringBuilder("[")
        var bytes = 2L
        for ((index, record) in records.withIndex()) {
            val text = (if (transfer) record.transferJson() else record.json()) ?: return null
            bytes += EntryDocumentCodec.utf8Size(text) + if (index == 0) 0 else 1
            if (bytes > EntryDocumentCodec.MAX_BYTES) return null
            if (index != 0) output.append(',')
            output.append(text)
        }
        return output.append(']').toString()
    }
    fun destroy() { owned?.forEach { it.destroy() }; owned = null }
    override fun toString() = "EntryDocument(redacted)"
}

/** Full entry-document representation and strict syntax/shape/parameter policy. Native engines
 * remain responsible for interpreting NOTE/SSH/PWD1/email payload bytes with their existing
 * validated material codecs. This class neither derives keys nor opens encrypted vaults.
 */
class EntryDocumentCodec {
    fun decode(text: String): EntryDocumentResult {
        if (text.length > MAX_BYTES || utf8Size(text) > MAX_BYTES) return failure(EntryDocumentError.TOO_LARGE)
        val parsed = try { EntryJsonText.parse(text) } catch (_: IllegalArgumentException) { return failure(EntryDocumentError.INVALID_JSON) }
        val values = (parsed as? EntryArray)?.values ?: return failure(EntryDocumentError.INVALID_JSON)
        if (values.size > MAX_ENTRIES) return failure(EntryDocumentError.TOO_MANY_ENTRIES)
        val records = ArrayList<EntryRecord>()
        return try {
            for (value in values) records.add(EntryRecord(value as? EntryObject ?: throw IllegalArgumentException()))
            EntryDocumentResult(EntryDocument(records), null)
        } catch (_: IllegalArgumentException) {
            records.forEach { it.destroy() }
            failure(EntryDocumentError.INVALID_ENTRY)
        }
    }
    /** Single public transfer operation for native adapters, including bounded re-encoding. */
    fun roundTrip(text: String): String? {
        val document = decode(text).document ?: return null
        return try { document.transferJson() } finally { document.destroy() }
    }
    private fun failure(error: EntryDocumentError) = EntryDocumentResult(null, error)
    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_DEPTH = 32
        const val MAX_ENTRIES = 2000
        const val MAX_VALUES = 200000
        internal fun utf8Size(text: String): Long {
            var bytes = 0L
            var index = 0
            while (index < text.length) {
                val char = text[index++]
                bytes += when {
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    char in '\ud800'..'\udbff' && index < text.length && text[index] in '\udc00'..'\udfff' -> { index++; 4 }
                    else -> 3
                }
            }
            return bytes
        }
    }
}
