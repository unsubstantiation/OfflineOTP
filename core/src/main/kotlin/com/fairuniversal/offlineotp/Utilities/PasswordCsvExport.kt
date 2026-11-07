package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.Database.Entry
import java.util.Locale

/**
 * Turns imported password entries back into a browser CSV, and applies an edited field back onto a
 * stored entry.
 *
 * The browser import keeps every source column (see [PasswordMaterial]) precisely so an entry can
 * be written back out in the file's own dialect. Chrome, Firefox and Safari disagree about the
 * header of that dialect, though, so what can be written back is narrower than what can be read:
 * this object only understands the headers it knows it round-trips.
 *
 * An entry that came from a LinSCP session, a legacy Thunderbird row or any other import carries
 * the same [PasswordMaterial] payload but a column set no browser understands, and exporting it as
 * a browser CSV would hand the user a file that silently drops columns. Those entries are reported
 * as [Support.NONE] instead, and the client offers the LinSCP/Thunderbird export instead.
 *
 * Callers must protect the returned bytes: they are a plaintext password export.
 */
object PasswordCsvExport {

    /** Which browser dialect a stored column set belongs to, and whether it can be written back. */
    enum class Dialect { CHROME, FIREFOX, SAFARI }

    sealed class Support {
        /** The entry's columns form a complete, known browser dialect. */
        class Exportable(val dialect: Dialect) : Support() {
            override fun toString(): String = "PasswordCsvExport.Support.Exportable($dialect)"
        }
        /** The entry has password material, but no known browser writes these columns. */
        object Foreign : Support() {
            override fun toString(): String = "PasswordCsvExport.Support.Foreign"
        }
        /** The entry carries no password material at all (OTP, note, archive, email…). */
        object NotPassword : Support() {
            override fun toString(): String = "PasswordCsvExport.Support.NotPassword"
        }
    }

    /** The editable fields of one password, in the vocabulary every browser shares. */
    const val FIELD_URL = "url"
    const val FIELD_USERNAME = "username"
    const val FIELD_PASSWORD = "password"

    /** The required column set of each dialect, plus what makes a header unambiguous. */
    private val REQUIRED = listOf(FIELD_URL, FIELD_USERNAME, FIELD_PASSWORD)

    /** Human-readable name of a dialect, used for the export warning and the file extension. */
    @JvmStatic
    fun browserName(dialect: Dialect): String = when (dialect) {
        Dialect.CHROME -> "Chrome"
        Dialect.FIREFOX -> "Firefox"
        Dialect.SAFARI -> "Safari"
    }

    /**
     * Decides whether [entry] can be exported as a browser CSV. The result depends only on the
     * stored columns, so an entry imported from Chrome stays a Chrome CSV even after its password
     * is edited here — the header is what identifies the dialect, not the file it came from.
     */
    @JvmStatic
    fun support(entry: Entry): Support {
        if (!entry.isPassword()) return Support.NotPassword
        val fields = try {
            entry.getPasswordFields()
        } catch (_: IllegalArgumentException) {
            // A PASSWORD entry always validates, so a payload that will not decode is not something
            // a browser could have written either.
            return Support.Foreign
        } catch (_: IllegalStateException) {
            return Support.Foreign
        }
        val columns = fields.columns().map { it.trim().lowercase(Locale.ROOT) }
        if (columns.any { it.isEmpty() } || columns.toSet().size != columns.size) return Support.Foreign
        if (!columns.containsAll(REQUIRED)) return Support.Foreign
        val names = columns.toSet()
        val dialect = when {
            "title" in names -> Dialect.SAFARI
            "name" in names -> Dialect.CHROME
            "httprealm" in names || "formactionorigin" in names || "guid" in names -> Dialect.FIREFOX
            else -> null
        }
        return if (dialect == null) Support.Foreign else Support.Exportable(dialect)
    }

    /**
     * Writes the given entries as one CSV in [dialect]'s header.
     *
     * @param entries password entries; each must report [Support.Exportable].
     * @param dialect the header to write; the first entry's own header is kept when every entry
     *   agrees on it, otherwise [canonicalHeader] supplies a complete one for the dialect.
     */
    @JvmStatic
    fun exportCsv(entries: List<Entry>, dialect: Dialect): ByteArray {
        require(entries.isNotEmpty()) { "No entries to export" }
        val decoded = ArrayList<PasswordMaterial.Fields>(entries.size)
        try {
            for (entry in entries) {
                // Fail closed rather than write a file that drops the columns the entry carries.
                require(support(entry) is Support.Exportable) {
                    "Only browser-imported password entries can be exported as a browser CSV"
                }
                decoded.add(entry.getPasswordFields())
            }
            val columns = headerFor(decoded, dialect)
            val rows = decoded.map { fields -> columns.map { column(it, fields) } }
            return ThunderbirdCsv.exportCsv(columns, rows)
        } finally {
            // Fields hold only Strings, which cannot be wiped, but dropping the references keeps the
            // caller's copies from outliving the export.
            decoded.clear()
        }
    }

    /**
     * The header written for [dialect]. The first entry's own header wins when every entry agrees on
     * the column set (it is the file dialect the user actually imported), otherwise the canonical
     * header for the dialect is used and missing columns are written empty.
     */
    private fun headerFor(decoded: List<PasswordMaterial.Fields>, dialect: Dialect): List<String> {
        val first = decoded.first().columns()
        val same = decoded.all { it.columns().size == first.size &&
            it.columns().map { c -> c.trim().lowercase(Locale.ROOT) } ==
            first.map { c -> c.trim().lowercase(Locale.ROOT) } }
        if (same && dialectOf(first) == dialect) return first
        return canonicalHeader(dialect).toList()
    }

    private fun dialectOf(columns: List<String>): Dialect? {
        val names = columns.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        if (!names.containsAll(REQUIRED)) return null
        return when {
            "title" in names -> Dialect.SAFARI
            "name" in names -> Dialect.CHROME
            "httprealm" in names || "formactionorigin" in names || "guid" in names -> Dialect.FIREFOX
            else -> null
        }
    }

    /**
     * A complete header for [dialect], so a file written from entries that came from mixed sources
     * still imports back into the browser it names.
     */
    @JvmStatic
    fun canonicalHeader(dialect: Dialect): List<String> = when (dialect) {
        Dialect.CHROME -> listOf("name", "url", "username", "password", "note")
        Dialect.FIREFOX -> listOf("url", "username", "password", "httpRealm", "formActionOrigin", "guid",
            "timeCreated", "timeLastUsed", "timePasswordChanged")
        Dialect.SAFARI -> listOf("Title", "URL", "Username", "Password", "Notes", "OTPAuth")
    }

    /**
     * One cell of the export: the stored value of [column] when the entry has that column, otherwise
     * a value mapped from the column's meaning in another dialect (Chrome's `name`/`note` against
     * Safari's `Title`/`Notes`, and so on). Absent stays empty rather than being invented.
     */
    private fun column(column: String, fields: PasswordMaterial.Fields): String {
        val direct = fields.value(column)
        if (direct.isNotEmpty()) return direct
        return when (column.trim().lowercase(Locale.ROOT)) {
            "name", "title" -> fields.value("name").ifBlank { fields.value("title") }
            "note", "notes" -> fields.value("note").ifBlank { fields.value("notes") }
            "url" -> fields.value("origin").ifBlank { fields.value("login_uri") }
            "username", "user" -> fields.value("user").ifBlank { fields.value("login_username") }
            "password", "pass" -> fields.value("login_password").ifBlank { fields.value("pass") }
            else -> ""
        }
    }

    /**
     * Applies an edited URL, username or password to [entry], leaving every other column (notes,
     * timestamps, GUIDs) untouched, and returns the replacement entry.
     *
     * The caller owns the result and must destroy the old entry once the replacement is committed.
     * Blank passwords are refused: an empty password cell would export an entry that cannot log in
     * anywhere, and the vault has no way to tell that apart from a deliberate empty password.
     *
     * @throws IllegalArgumentException when the entry is not an editable password entry or a value
     *   breaks the entry bounds. Nothing is modified in that case.
     */
    @JvmStatic
    fun withEditedFields(entry: Entry, url: String? = null, username: String? = null, password: String? = null): Entry {
        require(entry.isPassword()) { "Only password entries can be edited here" }
        val fields = entry.getPasswordFields()
        val pairs = ArrayList<Pair<String, String>>(fields.columns().size)
        for (index in fields.columns().indices) {
            val name = fields.columns()[index]
            val normalized = name.trim().lowercase(Locale.ROOT)
            val current = fields.valueAt(index)
            val replacement = when (normalized) {
                "url", "origin", "login_uri", "hostname", "host" -> url ?: current
                "username", "user", "login_username" -> username ?: current
                "password", "pass", "login_password" -> password ?: current
                else -> current
            }
            pairs.add(name to replacement)
        }
        val effectivePassword = pairs.lastOrNull { it.first.trim().lowercase(Locale.ROOT) == FIELD_PASSWORD }
            ?.second ?: pairs.firstOrNull { it.first.trim().lowercase(Locale.ROOT) == "pass" }?.second
        require(!effectivePassword.isNullOrEmpty()) { "A password cannot be empty" }
        val editedUrl = pairs.firstOrNull { it.first.trim().lowercase(Locale.ROOT) == FIELD_URL }?.second.orEmpty()
        val user = pairs.firstOrNull { it.first.trim().lowercase(Locale.ROOT) == FIELD_USERNAME }?.second.orEmpty()
        // Name the entry the way the import named it, from the columns it actually has.
        val issuer = pairs.firstOrNull { it.first.trim().lowercase(Locale.ROOT) == "name" }?.second
            ?.ifBlank { pairs.firstOrNull { it.first.trim().lowercase(Locale.ROOT) == "title" }?.second }
            ?.ifBlank { editedUrl }
            ?: editedUrl
        val label = user.ifBlank { "Password" }
        val replacement = Entry.passwordFields(label, issuer, pairs, ArrayList(entry.getTags()))
        replacement.setThumbnailName(entry.getThumbnailName())
        replacement.setColor(entry.getColor())
        return replacement
    }
}
