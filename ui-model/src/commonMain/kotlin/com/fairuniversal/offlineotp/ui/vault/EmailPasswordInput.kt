package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.EmailMaterial

/** Edit-only credentials. Blank means preserve, never implicit deletion. No stored secret seeds. */
data class EmailPasswordInput(val incoming: String = "", val outgoing: List<String> = emptyList()) {
    fun build(existing: Entry): Entry {
        require(existing.isEmail())
        val account = existing.getEmailAccount()
        require(outgoing.size == account.identities.size) { "Identity count changed" }
        require(incoming.length <= EmailMaterial.MAX_CREDENTIAL_CHARS)
        outgoing.forEach { require(it.length <= EmailMaterial.MAX_CREDENTIAL_CHARS) }
        if (incoming.isNotEmpty()) account.account.put("password", incoming)
        outgoing.forEachIndexed { index, password ->
            if (password.isNotEmpty()) account.identities[index].put("password", password)
        }
        val bytes = EmailMaterial.encode(account.account, account.identities)
        try {
            val replacement = Entry(existing.toJSON())
            try {
                replacement.setSecret(bytes)
                replacement.validate()
                return replacement
            } catch (error: Exception) { replacement.destroy(); throw error }
        } finally { bytes.fill(0) }
    }
    fun withoutSecrets() = EmailPasswordInput(outgoing = List(outgoing.size) { "" })
    override fun toString() = "EmailPasswordInput(redacted)"
    companion object {
        fun fromEntry(entry: Entry): EmailPasswordInput {
            require(entry.isEmail())
            return EmailPasswordInput(outgoing = List(entry.getEmailAccount().identities.size) { "" })
        }
    }
}
