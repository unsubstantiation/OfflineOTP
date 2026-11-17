package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import org.json.JSONArray
import org.json.JSONObject

/** Typed, lossless fields: imported extensions are editable without rebuilding a reduced account. */
data class EmailFieldInput(val name: String, val value: String, val kind: Kind) {
    enum class Kind { TEXT, NUMBER, BOOLEAN, OBJECT, ARRAY, NULL }
    fun putInto(json: JSONObject) {
        require(value.length <= EmailMaterial.MAX_BYTES)
        if (kind != Kind.TEXT) InputPolicy.jsonDepth(value)
        val parsed: Any = when (kind) {
            Kind.TEXT -> value
            Kind.NUMBER -> JSONArray("[$value]").let { require(it.length() == 1); it.get(0).also { n -> require(n is Number) } }
            Kind.BOOLEAN -> { require(value == "true" || value == "false"); value == "true" }
            Kind.OBJECT -> JSONObject(value)
            Kind.ARRAY -> JSONArray(value)
            Kind.NULL -> { require(value == "null"); JSONObject.NULL }
        }
        json.put(name, parsed)
    }
    override fun toString() = "EmailFieldInput(redacted)"
    companion object {
        fun fields(json: JSONObject): List<EmailFieldInput> = json.keys().asSequence().map { key ->
            val value = json.get(key)
            val kind = when (value) {
                JSONObject.NULL -> Kind.NULL
                is Boolean -> Kind.BOOLEAN
                is Number -> Kind.NUMBER
                is JSONObject -> Kind.OBJECT
                is JSONArray -> Kind.ARRAY
                else -> Kind.TEXT
            }
            EmailFieldInput(key, value.toString(), kind)
        }.toList()
        fun updated(fields: List<EmailFieldInput>, index: Int, value: String): List<EmailFieldInput> {
            val name = fields[index].name
            return fields.mapIndexed { i, old ->
                val linked = when {
                    name == "protocol" && old.name == "pop" -> (value == "1").toString()
                    name == "pop" && old.name == "protocol" -> if (value == "true") "1" else "0"
                    name == "encryption" && old.name == "starttls" -> (value == "1").toString()
                    name == "starttls" && old.name == "encryption" -> if (value == "true") "1" else "0"
                    else -> old.value
                }
                old.copy(value = if (i == index) value else linked)
            }
        }
        fun json(fields: List<EmailFieldInput>) = JSONObject().also { json -> fields.forEach { it.putInto(json) } }
    }
}

data class EmailEditInput(val issuer: String, val label: String, val hiddenLabel: String, val tags: String,
                          val thumbnail: String, val account: List<EmailFieldInput>, val identities: List<List<EmailFieldInput>>) {
    fun build(existing: Entry): Entry {
        require(existing.isEmail())
        listOf(issuer, label, hiddenLabel, thumbnail).forEach { InputPolicy.text(it, InputPolicy.MAX_TEXT_CHARS) }
        val bytes = EmailMaterial.encode(EmailFieldInput.json(account), identities.map { EmailFieldInput.json(it) })
        try {
            val result = Entry(existing.toJSON())
            try {
                result.setSecret(bytes)
                result.setIssuer(issuer, false); result.setLabel(label); result.setHiddenLabel(hiddenLabel)
                result.setTags(ArrayList(tags.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()))
                result.setThumbnailName(thumbnail)
                result.validate()
                return result
            } catch (error: Exception) { result.destroy(); throw error }
        } finally { bytes.fill(0) }
    }
    override fun toString() = "EmailEditInput(redacted)"
    companion object {
        fun fromEntry(entry: Entry): EmailEditInput {
            require(entry.isEmail())
            val email = entry.getEmailAccount()
            return EmailEditInput(entry.getIssuer(), entry.getLabel(), entry.getHiddenLabel(), entry.getTags().joinToString("\n"),
                entry.getThumbnailName(), EmailFieldInput.fields(email.account), email.identities.map { EmailFieldInput.fields(it) })
        }
    }
}
