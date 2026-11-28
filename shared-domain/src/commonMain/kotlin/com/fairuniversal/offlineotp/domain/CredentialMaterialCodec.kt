package com.fairuniversal.offlineotp.domain

class CredentialFields(val names: List<String>, val values: List<String>) {
    override fun toString() = "CredentialFields(redacted)"
}

/** Shared binary credential framing. Native engines retain ownership, crypto and OS access. */
class CredentialMaterialCodec {
    fun passwordEncode(names: List<String>, values: List<String>): ByteArray? = safely {
        require(names.size == values.size && names.size in 1..64)
        val fields = names.indices.flatMap { listOf(names[it], values[it]) }
        val bytes = encode(0x50574431, names.size, fields, 4 * 1024 * 1024, 65536)
        if (passwordDecode(bytes) == null) { bytes.fill(0); throw IllegalArgumentException() }
        bytes
    }
    fun passwordDecode(bytes: ByteArray): CredentialFields? = safely {
        require(bytes.size in 8..4 * 1024 * 1024)
        val reader = Reader(bytes); require(reader.int() == 0x50574431)
        val count = reader.int(); require(count in 1..64)
        val names = ArrayList<String>(); val values = ArrayList<String>()
        repeat(count) { names.add(reader.text(65536)); values.add(reader.text(65536)) }
        require(reader.done())
        val normalized = names.map { it.trim().lowercase() }
        require(normalized.none { it.isEmpty() } && normalized.toSet().size == count)
        require(normalized.containsAll(listOf("url", "username", "password")))
        CredentialFields(names, values)
    }
    fun sshEncode(publicKey: String, privateKey: String): ByteArray? = safely {
        require(publicKey.isNotBlank() || privateKey.isNotBlank())
        encode(null, 0, listOf(publicKey, privateKey), 65544, 32768)
    }
    fun sshDecode(bytes: ByteArray): CredentialFields? = safely {
        require(bytes.size in 8..65548)
        val reader = Reader(bytes)
        if (bytes.size >= 12 && reader.peek() == 0x53534831) reader.int()
        val publicKey = reader.text(32768); val privateKey = reader.text(32768)
        require(reader.done() && (publicKey.isNotBlank() || privateKey.isNotBlank()))
        CredentialFields(listOf("public", "private"), listOf(publicKey, privateKey))
    }
    /** FairEmail JSON preserves all native/unknown account and identity fields verbatim. */
    fun emailValid(bytes: ByteArray): Boolean = safely {
        require(bytes.isNotEmpty() && bytes.size <= 128 * 1024)
        val root = EntryJsonText.parse(bytes.decodeToString(throwOnInvalidSequence = true)) as EntryObject
        val account = root["fairmail_account"] as EntryObject
        val identities = (root["fairmail_identities"] as? EntryArray)?.values
            ?: listOf(root["fairmail_identity"] ?: throw IllegalArgumentException())
        require(identities.size in 1..128)
        fun integer(obj: EntryObject, key: String): Int? = (obj[key] as? EntryNumber)?.decimal?.toIntOrNull()
        fun text(obj: EntryObject, key: String): String = (obj[key] as? EntryText)?.value ?: throw IllegalArgumentException()
        fun connection(obj: EntryObject) {
            require(text(obj, "host").isNotBlank() && integer(obj, "port") in 1..65535)
            val encryption = integer(obj, "encryption") ?: (obj["starttls"] as? EntryBoolean)?.let { if (it.value) 1 else 0 }
            require(encryption in 0..2 && (integer(obj, "auth_type") ?: -1) >= 0)
            require(text(obj, "user").length <= 16384 && text(obj, "password").length <= 16384)
        }
        val protocol = integer(account, "protocol") ?: (account["pop"] as? EntryBoolean)?.let { if (it.value) 1 else 0 }
        require(protocol in 0..1); connection(account)
        for (value in identities) { val identity = value as EntryObject; connection(identity); require(text(identity, "email").contains('@')) }
        true
    } ?: false
    fun validate(kind: String, bytes: ByteArray): Boolean = when (kind) {
        "PASSWORD" -> passwordDecode(bytes) != null
        "SSH_KEY" -> sshDecode(bytes) != null
        "EMAIL" -> emailValid(bytes)
        else -> true
    }
    fun validateEncoded(kind: String, base32: String): Boolean {
        if (kind !in listOf("PASSWORD", "SSH_KEY", "EMAIL")) return true
        val bytes = safely { InterchangeEncoding.unbase32(base32) } ?: return false
        return try { validate(kind, bytes) } finally { bytes.fill(0) }
    }
    private fun encode(magic: Int?, count: Int, fields: List<String>, maxBytes: Int, maxChars: Int): ByteArray {
        val encoded = ArrayList<ByteArray>()
        try {
            var size = if (magic == null) 0L else 8L
            for (field in fields) {
                require(field.length <= maxChars)
                val bytes = field.encodeToByteArray(throwOnInvalidSequence = true); encoded.add(bytes)
                size += 4L + bytes.size; require(size <= maxBytes)
                if (magic == null) require(bytes.size <= 32768)
            }
            val out = ByteArray(size.toInt()); var offset = 0
            fun int(value: Int) { for (shift in 24 downTo 0 step 8) out[offset++] = (value ushr shift).toByte() }
            if (magic != null) { int(magic); int(count) }
            for (bytes in encoded) { int(bytes.size); bytes.copyInto(out, offset); offset += bytes.size }
            return out
        } finally { encoded.forEach { it.fill(0) } }
    }
    private class Reader(private val bytes: ByteArray) {
        private var offset = 0
        fun peek(): Int { val prior = offset; val value = int(); offset = prior; return value }
        fun int(): Int {
            require(offset <= bytes.size - 4)
            var value = 0; repeat(4) { value = (value shl 8) or (bytes[offset++].toInt() and 255) }; return value
        }
        fun text(limit: Int): String {
            val size = int(); require(size >= 0 && size <= bytes.size - offset)
            val value = bytes.decodeToString(offset, offset + size, throwOnInvalidSequence = true)
            offset += size; require(value.length <= limit); return value
        }
        fun done() = offset == bytes.size
    }
    private inline fun <T> safely(block: () -> T): T? = try { block() } catch (_: Exception) { null }
}
