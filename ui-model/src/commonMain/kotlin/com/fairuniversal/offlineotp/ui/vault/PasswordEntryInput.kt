package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.PasswordCsvExport

/** Edit-only browser/common password fields; never preload a stored password. */
data class PasswordEntryInput(val url: String = "", val username: String = "", val password: String = "") {
    fun build(existing: Entry): Entry {
        require(supports(existing)) { "Unsupported password columns" }
        return PasswordCsvExport.withEditedFields(existing,
            url = url, username = username, password = password.ifEmpty { null })
    }
    fun withoutPassword() = copy(password = "")
    override fun toString() = "PasswordEntryInput(redacted)"
    companion object {
        /** Avoid silently rewriting conflicting aliases or inventing missing columns. */
        fun supports(entry: Entry): Boolean = PasswordCsvExport.supportsCommonEdit(entry)
        fun fromEntry(entry: Entry): PasswordEntryInput {
            require(supports(entry)) { "Unsupported password columns" }
            val fields = entry.getPasswordFields()
            return PasswordEntryInput(fields.value(PasswordCsvExport.FIELD_URL), fields.value(PasswordCsvExport.FIELD_USERNAME))
        }
    }
}
