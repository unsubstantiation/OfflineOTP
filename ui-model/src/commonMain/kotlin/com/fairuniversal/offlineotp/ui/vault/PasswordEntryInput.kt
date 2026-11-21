package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.PasswordCsvExport
import com.fairuniversal.offlineotp.Utilities.PasswordMaterial
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog
import com.fairuniversal.offlineotp.ui.portable.PortableEmailField
import java.util.Locale

/** Edit-only password / archive fields; never preload a stored password. */
data class PasswordEntryInput(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val issuer: String = "",
    val label: String = "",
    val tags: String? = null,
    val thumbnail: String? = null,
    val extraFields: List<PortableEmailField>? = null,
    val customColumnsMode: Boolean = false,
    val archiveMode: Boolean = false,
    val archiveSizeBytes: Int = 0
) {
    fun build(existing: Entry): Entry {
        require(thumbnail.isNullOrEmpty() || thumbnail in ThumbnailCatalog.NAMES || thumbnail == existing.getThumbnailName()) {
            "Unknown thumbnail"
        }
        val parsedTags = tags?.let { raw ->
            ArrayList(raw.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        }
        if (archiveMode || existing.isPasswordArchive()) {
            require(existing.isPasswordArchive()) { "Expected password archive entry" }
            val replacement = Entry(existing.toJSON())
            try {
                replacement.setColor(existing.getColor())
                replacement.setIssuer(issuer, false)
                replacement.setLabel(label.ifBlank { existing.getLabel() })
                if (parsedTags != null) replacement.setTags(parsedTags)
                if (!thumbnail.isNullOrEmpty()) replacement.setThumbnailName(thumbnail)
                replacement.validate()
                return replacement
            } catch (error: Exception) {
                replacement.destroy()
                throw error
            }
        }
        if (customColumnsMode) {
            require(existing.isPassword()) { "Expected password entry" }
            val orig = existing.getPasswordFields()
            val submitted = extraFields.orEmpty()
            require(submitted.size == orig.columns().size) { "Password column count changed" }
            val pairs = ArrayList<Pair<String, String>>(orig.columns().size)
            for (idx in orig.columns().indices) {
                val colName = orig.columns()[idx]
                val norm = colName.trim().lowercase(Locale.ROOT)
                val typed = submitted[idx].value
                val nextVal = if (norm in PASSWORD_COLUMN_NAMES && typed.isEmpty()) orig.valueAt(idx) else typed
                pairs.add(colName to nextVal)
            }
            val effectivePw = pairs.lastOrNull { it.first.trim().lowercase(Locale.ROOT) == PasswordCsvExport.FIELD_PASSWORD }?.second
                ?: pairs.firstOrNull { it.first.trim().lowercase(Locale.ROOT) in PASSWORD_COLUMN_NAMES }?.second
            require(!effectivePw.isNullOrEmpty()) { "A password cannot be empty" }
            val material = PasswordMaterial.encodeFields(pairs)
            try {
                val replacement = Entry(existing.toJSON())
                try {
                    replacement.setSecret(material)
                    replacement.setColor(existing.getColor())
                    replacement.setIssuer(issuer, false)
                    replacement.setLabel(label.ifBlank { existing.getLabel() })
                    if (parsedTags != null) replacement.setTags(parsedTags)
                    if (!thumbnail.isNullOrEmpty()) replacement.setThumbnailName(thumbnail)
                    replacement.validate()
                    return replacement
                } catch (error: Exception) {
                    replacement.destroy()
                    throw error
                }
            } finally {
                material.fill(0)
            }
        }

        require(supports(existing)) { "Unsupported password columns" }
        val replacement = PasswordCsvExport.withEditedFields(
            existing,
            url = url,
            username = username,
            password = password.ifEmpty { null }
        )
        try {
            if (extraFields != null) {
                val currentFields = replacement.getPasswordFields()
                val extraMap = extraFields.associate { it.name.trim().lowercase(Locale.ROOT) to it.value }
                var changedExtra = false
                val updatedPairs = currentFields.columns().indices.map { idx ->
                    val colName = currentFields.columns()[idx]
                    val norm = colName.trim().lowercase(Locale.ROOT)
                    val curVal = currentFields.valueAt(idx)
                    if (norm !in CORE_COMMON_COLUMNS && norm in extraMap) {
                        val nextVal = extraMap.getValue(norm)
                        if (nextVal != curVal) changedExtra = true
                        colName to nextVal
                    } else {
                        colName to curVal
                    }
                }
                if (changedExtra) {
                    val material = PasswordMaterial.encodeFields(updatedPairs)
                    try {
                        replacement.setSecret(material)
                        val editedUrl = updatedPairs.firstOrNull { it.first.trim().lowercase(Locale.ROOT) == PasswordCsvExport.FIELD_URL }?.second.orEmpty()
                        val computedIssuer = updatedPairs.firstOrNull { it.first.trim().lowercase(Locale.ROOT) == "name" }?.second
                            ?.ifBlank { updatedPairs.firstOrNull { it.first.trim().lowercase(Locale.ROOT) == "title" }?.second }
                            ?.ifBlank { editedUrl }
                            ?: editedUrl
                        replacement.setIssuer(computedIssuer, false)
                    } finally {
                        material.fill(0)
                    }
                }
            }
            if (parsedTags != null) replacement.setTags(parsedTags)
            if (!thumbnail.isNullOrEmpty()) replacement.setThumbnailName(thumbnail)
            replacement.validate()
            return replacement
        } catch (error: Exception) {
            replacement.destroy()
            throw error
        }
    }

    fun withoutPassword() = copy(
        password = "",
        extraFields = extraFields?.map { field ->
            if (field.kind == PortableEmailField.KIND_SECRET) field.copy(value = "") else field
        }
    )

    override fun toString() = "PasswordEntryInput(redacted)"

    companion object {
        private val CORE_COMMON_COLUMNS = setOf("url", "username", "password")
        private val PASSWORD_COLUMN_NAMES = setOf("password", "pass", "login_password")

        /** Avoid silently rewriting conflicting aliases or inventing missing columns. */
        fun supports(entry: Entry): Boolean = PasswordCsvExport.supportsCommonEdit(entry)

        /** Every password and password-archive entry can open the full password editor. */
        fun supportsAny(entry: Entry): Boolean = entry.isPassword() || entry.isPasswordArchive()

        fun fromEntry(entry: Entry): PasswordEntryInput {
            require(supports(entry)) { "Unsupported password columns" }
            val fields = entry.getPasswordFields()
            val extras = fields.columns().indices.mapNotNull { idx ->
                val name = fields.columns()[idx]
                if (name.trim().lowercase(Locale.ROOT) in CORE_COMMON_COLUMNS) null
                else PortableEmailField(name = name, value = fields.valueAt(idx), kind = PortableEmailField.KIND_TEXT)
            }
            return PasswordEntryInput(
                url = fields.value(PasswordCsvExport.FIELD_URL),
                username = fields.value(PasswordCsvExport.FIELD_USERNAME),
                tags = entry.getTags().joinToString("\n"),
                thumbnail = entry.getThumbnailName(),
                extraFields = extras
            )
        }

        fun fromAnyEntry(entry: Entry): PasswordEntryInput {
            if (supports(entry)) return fromEntry(entry)
            if (entry.isPasswordArchive()) {
                return PasswordEntryInput(
                    issuer = entry.getIssuer(),
                    label = entry.getLabel(),
                    tags = entry.getTags().joinToString("\n"),
                    thumbnail = entry.getThumbnailName(),
                    archiveMode = true,
                    archiveSizeBytes = entry.getSecret()?.size ?: 0
                )
            }
            require(entry.isPassword()) { "Unsupported entry type" }
            val fields = entry.getPasswordFields()
            val allCols = fields.columns().indices.map { idx ->
                val name = fields.columns()[idx]
                val isSecret = name.trim().lowercase(Locale.ROOT) in PASSWORD_COLUMN_NAMES
                PortableEmailField(
                    name = name,
                    value = if (isSecret) "" else fields.valueAt(idx),
                    kind = if (isSecret) PortableEmailField.KIND_SECRET else PortableEmailField.KIND_TEXT
                )
            }
            return PasswordEntryInput(
                issuer = entry.getIssuer(),
                label = entry.getLabel(),
                tags = entry.getTags().joinToString("\n"),
                thumbnail = entry.getThumbnailName(),
                extraFields = allCols,
                customColumnsMode = true
            )
        }
    }
}
