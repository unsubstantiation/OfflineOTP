package com.fairuniversal.offlineotp.domain

class PreferenceRule(val key: String, val kind: String)
class PreferencesReply(val json: String?, val error: String?, val source: String,
    val accepted: List<String>, val unknown: List<String>, val refused: List<String>, val includesSecrets: Boolean) {
    override fun toString() = "PreferencesReply(redacted)"
}
/** Catalogs remain native because settings address a named client, not similarly spelled OS keys.
 * Complete validation precedes the native mutation port; no key or value is persisted here.
 */
class PreferencesInterchangeCodec {
    fun decode(text: String, platform: String, rules: List<PreferenceRule>, deviceBound: List<String>): PreferencesReply {
        fun failure(error: String, source: String = "") = PreferencesReply(null, error, source, emptyList(), emptyList(), emptyList(), false)
        if (text.isEmpty() || text.length > 524288 || EntryDocumentCodec.utf8Size(text) > 524288) return failure("SIZE")
        return try {
            val root = EntryJsonText.parse(text) as? EntryObject ?: return failure("DOCUMENT")
            if ((root["format"] as? EntryText)?.value != "offlineotp.preferences") return failure("DOCUMENT")
            if ((root["version"] as? EntryNumber)?.decimal != "1") return failure("VERSION")
            require(root["includesSecrets"] == null || root["includesSecrets"] is EntryBoolean)
            val source = (root["platform"] as? EntryText)?.value ?: ""
            if (source != platform) return failure("PLATFORM", source)
            val settings = root["settings"] as? EntryObject ?: return failure("SETTINGS")
            if (settings.fields.size > 512) return failure("KEYS")
            val catalog = rules.associate { it.key to it.kind }
            val accepted = ArrayList<String>(); val unknown = ArrayList<String>(); val refused = ArrayList<String>()
            var secrets = (root["includesSecrets"] as? EntryBoolean)?.value == true
            for ((key, value) in settings.fields) {
                val kind = catalog[key]
                if (key in deviceBound || kind == "DEVICE_BOUND") { refused.add(key); continue }
                if (kind == null) { unknown.add(key); continue }
                when (kind) {
                    "BOOLEAN" -> require(value is EntryBoolean)
                    "INTEGER" -> require(value is EntryNumber && value.decimal.toIntOrNull() != null)
                    "STRING", "SECRET" -> require(value is EntryText && value.value.length <= 65536)
                    "STRING_SET" -> require(value is EntryArray && value.values.size <= 256 && value.values.all { it is EntryText && it.value.length <= 65536 })
                    else -> throw IllegalArgumentException()
                }
                accepted.add(key); if (kind == "SECRET") secrets = true
            }
            PreferencesReply(root.json(), null, source, accepted.sorted(), unknown.sorted(), refused.sorted(), secrets)
        } catch (_: Exception) { failure("VALUE") }
    }
}
