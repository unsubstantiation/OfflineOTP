package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import org.json.JSONObject

/** Only explicitly editable transport metadata. Never contains stored passwords/tokens. */
data class EmailConnectionInput(val host: String, val port: String, val encryption: Int, val username: String) {
    internal fun applyTo(json: JSONObject) {
        require(host.isNotBlank()); InputPolicy.text(host, InputPolicy.MAX_TEXT_CHARS)
        InputPolicy.text(username, EmailMaterial.MAX_CREDENTIAL_CHARS)
        val number = port.toIntOrNull() ?: throw IllegalArgumentException("Invalid port")
        require(number in 1..65535); require(encryption in 0..2)
        json.put("host", host.trim()).put("port", number).put("encryption", encryption).put("user", username.trim())
    }
    override fun toString() = "EmailConnectionInput(redacted)"
    companion object {
        internal fun from(json: JSONObject) = EmailConnectionInput(json.optString("host").orEmpty(), json.optInt("port").toString(),
            if (json.has("encryption")) json.optInt("encryption") else if (json.optBoolean("starttls")) 1 else 0,
            json.optString("user").orEmpty())
    }
}

data class EmailSettingsInput(val protocol: Int, val incoming: EmailConnectionInput, val outgoing: List<EmailConnectionInput>) {
    fun build(existing: Entry): Entry {
        require(existing.isEmail()); require(protocol in 0..1)
        val account = existing.getEmailAccount()
        require(outgoing.size == account.identities.size)
        account.account.put("protocol", protocol)
        // Keep legacy compatibility flags consistent if they were present in the import.
        if (account.account.has("pop")) account.account.put("pop", protocol == 1)
        fun apply(input: EmailConnectionInput, json: JSONObject) {
            input.applyTo(json)
            if (json.has("starttls")) json.put("starttls", input.encryption == 1)
        }
        apply(incoming, account.account)
        outgoing.forEachIndexed { index, connection -> apply(connection, account.identities[index]) }
        val bytes = EmailMaterial.encode(account.account, account.identities)
        try {
            val replacement = Entry(existing.toJSON())
            try { replacement.setSecret(bytes); replacement.validate(); return replacement }
            catch (error: Exception) { replacement.destroy(); throw error }
        } finally { bytes.fill(0) }
    }
    override fun toString() = "EmailSettingsInput(redacted)"
    companion object {
        fun fromEntry(entry: Entry): EmailSettingsInput {
            require(entry.isEmail())
            val account = entry.getEmailAccount()
            val protocol = if (account.account.has("protocol")) account.account.optInt("protocol") else if (account.account.optBoolean("pop")) 1 else 0
            return EmailSettingsInput(protocol, EmailConnectionInput.from(account.account), account.identities.map { EmailConnectionInput.from(it) })
        }
    }
}
