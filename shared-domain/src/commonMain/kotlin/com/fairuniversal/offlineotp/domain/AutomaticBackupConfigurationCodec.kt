package com.fairuniversal.offlineotp.domain

/** Local vault-encrypted configuration, not an export. Native bookmarks are opaque but bounded.
 * Signing material is never permitted in the persistent document, only its reload requirement.
 * Immutable JSON strings are sensitive and cannot promise physical zeroization.
 */
class AutomaticBackupConfigurationCodec {
    fun canonical(text: String): String? {
        if (text.isEmpty() || text.length > 65536 || EntryDocumentCodec.utf8Size(text) > 65536) return null
        val keys = arrayListOf<ByteArray>()
        return try {
            val root = EntryJsonText.parse(text) as? EntryObject ?: return null
            require(root.fields.keys.all { it in setOf("version", "folder", "password", "minutes", "keep", "mode", "recipientKeys", "requiresSigning", "bookmark") })
            fun string(key: String) = (root[key] as? EntryText)?.value ?: error("Invalid configuration")
            fun number(key: String) = (root[key] as? EntryNumber)?.decimal?.toIntOrNull() ?: error("Invalid configuration")
            val version = number("version"); val folder = string("folder"); val password = string("password")
            require(folder.length in 1..4096 && BackupAutomationPolicy().timing(number("minutes"), number("keep")))
            require(root["requiresSigning"] == null || root["requiresSigning"] is EntryBoolean)
            val signing = (root["requiresSigning"] as? EntryBoolean)?.value == true
            when (version) {
                1 -> require(password.length in 1..EntryMetadata.MAX_TEXT_CHARS && root["mode"] == null && root["recipientKeys"] == null && !signing)
                2 -> {
                    require(string("mode") == "openpgp" && password.isEmpty())
                    val recipients = root["recipientKeys"] as? EntryArray ?: error("Invalid configuration")
                    require(recipients.values.size in 1..64)
                    recipients.values.forEach {
                        val value = (it as? EntryText)?.value ?: error("Invalid recipient")
                        require(value.length <= 43692)
                        keys.add(InterchangeEncoding.unbase64(value))
                    }
                    require(BackupAutomationPolicy().validRecipients(keys))
                }
                3 -> require(string("mode") == "vault-copy" && password.isEmpty() && root["recipientKeys"] == null && !signing)
                else -> error("Invalid version")
            }
            root["bookmark"]?.let {
                val bookmark = it as? EntryText ?: error("Invalid bookmark")
                require(bookmark.value.length <= 43692)
                val bytes = InterchangeEncoding.unbase64(bookmark.value)
                try { require(bytes.size <= 32768) } finally { bytes.fill(0) }
            }
            root.json()
        } catch (_: Exception) { null }
        finally { keys.forEach { it.fill(0) } }
    }
    fun encode(folder: String, password: String, minutes: Int, keep: Int, recipients: List<String>, requiresSigning: Boolean, vaultCopy: Boolean): String? {
        if (folder.length !in 1..4096 || password.length > EntryMetadata.MAX_TEXT_CHARS ||
            !BackupAutomationPolicy().timing(minutes, keep) || recipients.size > 64 ||
            recipients.sumOf { it.length.toLong() } > 44032) return null
        val version = if (vaultCopy) 3 else if (recipients.isNotEmpty()) 2 else 1
        val fields = linkedMapOf<String, EntryValue>("version" to EntryNumber(version.toString()), "folder" to EntryText(folder),
            "password" to EntryText(password), "minutes" to EntryNumber(minutes.toString()), "keep" to EntryNumber(keep.toString()))
        if (version == 3) fields["mode"] = EntryText("vault-copy")
        if (version == 2) { fields["mode"] = EntryText("openpgp"); fields["recipientKeys"] = EntryArray(recipients.map { EntryText(it) }) }
        fields["requiresSigning"] = EntryBoolean(requiresSigning)
        if (vaultCopy && recipients.isNotEmpty()) return null
        return canonical(EntryObject(fields).json())
    }
}
