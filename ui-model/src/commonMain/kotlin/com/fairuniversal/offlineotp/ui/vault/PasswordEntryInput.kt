package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.PasswordCsvExport
import java.util.Locale

/** Edit-only browser/common password fields; never preload a stored password. */
data class PasswordEntryInput(val url: String = "", val username: String = "", val password: String = "") {
    fun build(existing: Entry): Entry {
        require(supports(existing)) { "Unsupported password columns" }
        return PasswordCsvExport.withEditedFields(existing,
        url = url, username = username, password = password.ifEmpty { null }).also {
        it.setDetailsVisible(existing.areDetailsVisible())
        it.setLastUsed(existing.getLastUsed())
        it.setUsedFrequency(existing.getUsedFrequency())
    }
    }
    fun withoutPassword() = copy(password = "")
    override fun toString() = "PasswordEntryInput(redacted)"
    companion object {
        /** Avoid silently rewriting conflicting aliases or inventing missing columns. */
        fun supports(entry: Entry): Boolean {
            if (!entry.isPassword()) return false
            val columns = entry.getPasswordFields().columns().map { it.trim().lowercase(Locale.ROOT) }
            return listOf("url", "username", "password").all { name -> columns.count { it == name } == 1 } &&
                columns.none { it in setOf("origin", "login_uri", "hostname", "host", "user", "login_username", "pass", "login_password") }
        }
        fun fromEntry(entry: Entry): PasswordEntryInput {
            require(supports(entry)) { "Unsupported password columns" }
            val fields = entry.getPasswordFields()
            return PasswordEntryInput(fields.value(PasswordCsvExport.FIELD_URL), fields.value(PasswordCsvExport.FIELD_USERNAME))
        }
    }
}
