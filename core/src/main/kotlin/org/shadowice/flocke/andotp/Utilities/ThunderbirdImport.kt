package org.shadowice.flocke.andotp.Utilities

import org.shadowice.flocke.andotp.Database.Entry
import java.util.Locale

/** Creates Thunderbird credential entries; the caller commits them atomically. */
object ThunderbirdImport {
    const val MAX_BATCH_PAYLOAD_BYTES = PasswordImport.MAX_BATCH_PAYLOAD_BYTES

    /**
     * Prepare Thunderbird CSV bytes (Firefox/Thunderbird export or hostname-based variant)
     * into PASSWORD vault entries. The columns are preserved verbatim so re-export is
     * lossless; unknown columns are retained.
     */
    @JvmStatic
    fun prepare(bytes: ByteArray): ArrayList<Entry> {
        require(bytes.isNotEmpty() && bytes.size <= ThunderbirdCsv.MAX_FILE_BYTES)
        val parsed = ThunderbirdCsv.parse(bytes) as? ThunderbirdCsv.Result.Success
            ?: throw IllegalArgumentException("Unsupported or invalid Thunderbird CSV")
        val result = ArrayList<Entry>()
        try {
            var total = 0L
            parsed.rows.forEach { row ->
                // Normalize to PasswordMaterial-compatible columns: map hostname→url, host→url, etc.
                val normalizedColumns = parsed.columns.map { col ->
                    when (col.trim().lowercase(Locale.ROOT)) {
                        "hostname", "host", "origin", "login_uri" -> "url"
                        "login_username", "user" -> "username"
                        "login_password", "pass" -> "password"
                        else -> col
                    }
                }
                // Deduplicate after normalization: if both hostname and url were present, keep first
                val seen = LinkedHashMap<String, String>()
                normalizedColumns.forEachIndexed { idx, n ->
                    val key = n.trim().lowercase(Locale.ROOT)
                    if (!seen.containsKey(key)) seen[key] = row.valueAt(idx)
                }
                val finalColumns = seen.keys.toList()
                val finalValues = seen.values.toList()
                val entry = encodeAsPassword(finalColumns, finalValues)
                result.add(entry)
                total += entry.getSecretSize()
                require(total <= MAX_BATCH_PAYLOAD_BYTES) { "Import is too large" }
            }
            return result
        } catch (error: Exception) {
            result.forEach { it.destroy() }
            throw error
        }
    }

    private fun encodeAsPassword(columns: List<String>, values: List<String>): Entry {
        // Delegate to Entry.password which handles vault encoding, issuer/label and validation.
        // Columns already normalized to url/username/password.
        return Entry.password(columns, BrowserPasswordCsv.Row(values))
    }

    /**
     * Export the given PASSWORD entries as Thunderbird-compatible CSV (Firefox/Thunderbird
     * import format). Each entry's stored columns are read; if an entry lacks a url/hostname
     * field its issuer is used. The result is UTF-8 with canonical header
     * url,username,password,httpRealm,formActionOrigin,guid,timeCreated,timeLastUsed,timePasswordChanged
     * plus any extra columns the vault actually holds (preserved for round-trip).
     */
    @JvmStatic
    fun exportCsv(selected: List<Entry>): ByteArray {
        require(selected.isNotEmpty()) { "No entries to export" }
        require(selected.all { it.isPassword() }) { "Only password entries can be exported as Thunderbird" }
        val rows = ArrayList<List<String>>()
        val headerSet = LinkedHashSet<String>()
        headerSet.addAll(ThunderbirdCsv.EXPORT_COLUMNS)
        // First pass: collect all extra column names present in entries
        val decoded = selected.map { it.getPasswordFields() }
        decoded.forEach { fields ->
            fields.columns().forEach { col ->
                val n = col.trim().lowercase(Locale.ROOT)
                // Map Thunderbird aliases to canonical header
                val canonical = when (n) {
                    "hostname", "host", "origin", "login_uri" -> "url"
                    "login_username", "user" -> "username"
                    "login_password", "pass" -> "password"
                    else -> col
                }
                if (canonical.lowercase(Locale.ROOT) !in headerSet.map { it.lowercase(Locale.ROOT) }) {
                    headerSet.add(canonical)
                }
            }
        }
        val header = headerSet.toList()
        decoded.forEach { fields ->
            val row = header.map { h ->
                val n = h.lowercase(Locale.ROOT)
                // Try direct, then aliases
                var v = fields.value(h)
                if (v.isEmpty()) {
                    v = when (n) {
                        "url" -> fields.value("hostname").ifBlank { fields.value("host") }.ifBlank { fields.value("login_uri") }.ifBlank { fields.value("origin") }
                        "username" -> fields.value("login_username").ifBlank { fields.value("user") }
                        "password" -> fields.value("login_password").ifBlank { fields.value("pass") }
                        else -> ""
                    }
                }
                // Also check case-insensitive variant
                if (v.isEmpty()) {
                    for (c in fields.columns()) {
                        if (c.trim().equals(h, ignoreCase = true)) { v = fields.value(c); break }
                    }
                }
                v
            }
            rows.add(row)
        }
        return ThunderbirdCsv.exportCsv(header, rows)
    }
}
