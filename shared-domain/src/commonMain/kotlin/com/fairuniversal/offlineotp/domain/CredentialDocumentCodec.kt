package com.fairuniversal.offlineotp.domain

/** LinSCP public format/shape policy above native AES/Argon2/HMAC providers and key ownership. */
class CredentialDocumentCodec {
    fun linScp(text: String, bundle: Boolean): String? = try {
        require(text.length <= EntryDocumentCodec.MAX_BYTES && EntryDocumentCodec.utf8Size(text) <= EntryDocumentCodec.MAX_BYTES)
        val root = EntryJsonText.parse(text)
        if (bundle) {
            val document = root as EntryObject
            if (document["version"] != null) require((document["version"] as? EntryNumber)?.decimal == "1")
            require((document["sessions_ini"] as? EntryText)?.value?.isNotBlank() == true)
            if (document["sessions_key_b64"] != null) require(document["sessions_key_b64"] is EntryText)
        } else {
            val rows = (root as EntryArray).values
            require(rows.size in 1..EntryDocumentCodec.MAX_ENTRIES)
            var supported = 0
            for (value in rows) {
                val row = value as EntryObject
                val kind = (row["type"] as? EntryText)?.value
                if (kind !in listOf("SSH_KEY", "PASSWORD")) continue
                supported++
                val label = (row["label"] as? EntryText)?.value ?: throw IllegalArgumentException()
                require(label.isNotBlank() && label.length <= EntryMetadata.MAX_TEXT_CHARS)
                require(CredentialMaterialCodec().validateEncoded(kind!!, (row["secret"] as EntryText).value))
            }
            require(supported > 0)
        }
        root.json()
    } catch (_: Exception) { null }
    fun encryptedLinScp(prefix: ByteArray, totalBytes: Int): Boolean = totalBytes in 52..EntryDocumentCodec.MAX_BYTES &&
        prefix.size >= 8 && prefix.take(8) == "LSCPVLT2".encodeToByteArray().toList()
    override fun toString() = "CredentialDocumentCodec(redacted)"
}
