package org.shadowice.flocke.andotp.Utilities

import org.shadowice.flocke.andotp.Database.Entry
import java.util.Locale

/** Creates Thunderbird credential entries; the caller commits them atomically.
 *
 * FairEmail and Thunderbird now share the same EMAIL vault representation so an
 * account imported from one can be exported for the other without loss. Thunderbird's
 * CSV export (Firefox `Export Logins` format: url/hostname, username, password, …) is
 * grouped by username and turned into `EmailMaterial.Account`s, then stored as
 * `EMAIL` entries — the same type FairEmail uses.
 */
object ThunderbirdImport {
    const val MAX_BATCH_PAYLOAD_BYTES = PasswordImport.MAX_BATCH_PAYLOAD_BYTES

    /**
     * Prepare Thunderbird CSV bytes (Firefox/Thunderbird export or hostname-based variant)
     * into EMAIL vault entries that are indistinguishable from FairEmail imports. The
     * underlying `EmailMaterial` representation is the single source of truth for both
     * exporters.
     */
    @JvmStatic
    fun prepare(bytes: ByteArray): ArrayList<Entry> {
        require(bytes.isNotEmpty() && bytes.size <= ThunderbirdCsv.MAX_FILE_BYTES)
        val accounts = try {
            EmailMaterial.importThunderbirdCsv(bytes)
        } catch (e: Exception) {
            throw IllegalArgumentException("Unsupported or invalid Thunderbird CSV", e)
        }
        val result = ArrayList<Entry>(accounts.size)
        try {
            var total = 0L
            for (account in accounts) {
                val entry = Entry.email(account.account, account.identity, account.identities)
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

    /**
     * Export the given EMAIL entries (and, for old vaults, PASSWORD entries) as a
     * Thunderbird-compatible CSV. EMAIL entries are converted via the shared
     * `EmailMaterial` model so a FairEmail import can be exported for Thunderbird;
     * legacy PASSWORD entries are handled via their stored columns.
     */
    @JvmStatic
    fun exportCsv(selected: List<Entry>): ByteArray {
        require(selected.isNotEmpty()) { "No entries to export" }
        require(selected.all { it.isEmail() || it.isPassword() }) { "Only email/password entries can be exported as Thunderbird" }
        val emailEntries = selected.filter { it.isEmail() }
        val passwordEntries = selected.filter { it.isPassword() }
        if (emailEntries.isNotEmpty() && passwordEntries.isEmpty()) {
            return EmailMaterial.exportThunderbirdCsv(emailEntries.map { it.getEmailAccount() })
        }
        if (passwordEntries.isNotEmpty() && emailEntries.isEmpty()) {
            // Legacy path: old Thunderbird imports that were stored as PASSWORD entries
            val rows = ArrayList<List<String>>()
            val headerSet = LinkedHashSet<String>()
            headerSet.addAll(ThunderbirdCsv.EXPORT_COLUMNS)
            val decoded = passwordEntries.map { it.getPasswordFields() }
            decoded.forEach { fields ->
                fields.columns().forEach { col ->
                    val n = col.trim().lowercase(Locale.ROOT)
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
                    var v = fields.value(h)
                    if (v.isEmpty()) {
                        v = when (n) {
                            "url" -> fields.value("hostname").ifBlank { fields.value("host") }.ifBlank { fields.value("login_uri") }.ifBlank { fields.value("origin") }
                            "username" -> fields.value("login_username").ifBlank { fields.value("user") }
                            "password" -> fields.value("login_password").ifBlank { fields.value("pass") }
                            else -> ""
                        }
                    }
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
        // Mixed: export EMAIL accounts plus legacy PASSWORD rows into one CSV
        val emailCsv = if (emailEntries.isNotEmpty()) EmailMaterial.exportThunderbirdCsv(emailEntries.map { it.getEmailAccount() }) else null
        val passwordCsv = if (passwordEntries.isNotEmpty()) {
            // Reuse single-type path for password portion
            exportCsv(passwordEntries)
        } else null
        // Both are Thunderbird CSVs with same canonical header; merge rows
        if (emailCsv != null && passwordCsv != null) {
            val emailParsed = ThunderbirdCsv.parse(emailCsv) as ThunderbirdCsv.Result.Success
            val passParsed = ThunderbirdCsv.parse(passwordCsv) as ThunderbirdCsv.Result.Success
            // Union of headers (both should be EXPORT_COLUMNS)
            val header = (emailParsed.columns + passParsed.columns).distinct()
            val rows = ArrayList<List<String>>()
            fun collect(parsed: ThunderbirdCsv.Result.Success, header: List<String>) {
                val idxMap = header.map { h -> parsed.columns.indexOfFirst { it.equals(h, true) } }
                for (r in parsed.rows) {
                    rows.add(header.indices.map { i -> val idx = idxMap[i]; if (idx >= 0) r.valueAt(idx) else "" })
                }
            }
            collect(emailParsed, header)
            collect(passParsed, header)
            return ThunderbirdCsv.exportCsv(header, rows)
        }
        return emailCsv ?: passwordCsv!!
    }
}
