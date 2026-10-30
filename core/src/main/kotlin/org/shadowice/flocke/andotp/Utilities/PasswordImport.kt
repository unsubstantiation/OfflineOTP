package org.shadowice.flocke.andotp.Utilities

import org.shadowice.flocke.andotp.Database.Entry

/** Creates owned entries only; the caller commits them atomically to an unlocked vault. */
object PasswordImport {
    enum class Mode { INDIVIDUAL, ARCHIVE }
    // Every individual entry repeats the CSV headers. Bound expansion before building a batch.
    const val MAX_BATCH_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun prepare(bytes: ByteArray, filename: String, mode: Mode): ArrayList<Entry> {
        require(bytes.isNotEmpty() && bytes.size <= BrowserPasswordCsv.MAX_FILE_BYTES)
        if (mode == Mode.ARCHIVE) return arrayListOf(Entry.passwordArchive(filename, bytes))
        val parsed = BrowserPasswordCsv.parse(bytes) as? BrowserPasswordCsv.Result.Success
            ?: throw IllegalArgumentException("Unsupported or invalid browser CSV")
        val result = ArrayList<Entry>()
        try {
            var total = 0L
            parsed.rows.forEach {
                val entry = Entry.password(parsed.columns, it)
                result.add(entry) // Ensure it is wiped if the size check fails.
                total += entry.getSecretSize()
                require(total <= MAX_BATCH_PAYLOAD_BYTES) { "Import is too large" }
            }
            return result
        } catch (error: Exception) {
            result.forEach { it.destroy() }
            throw error
        }
    }
}
