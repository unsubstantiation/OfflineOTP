package com.fairuniversal.offlineotp.domain

enum class EntryRecordKind { TOTP, HOTP, MOTP, STEAM, NOTE, SSH_KEY, PASSWORD, PASSWORD_ARCHIVE, EMAIL }
enum class EntryRecordAlgorithm { SHA1, SHA256, SHA512 }

/** Kind-discriminated parameters, independent of the crypto implementation and native engine. */
class EntryParameters(val kind: EntryRecordKind, val algorithm: EntryRecordAlgorithm, val period: Int, val digits: Int, val counter: Long) {
    init {
        validate(period, digits, counter)
        require(kind != EntryRecordKind.STEAM || digits == 5)
    }
    override fun toString() = "EntryParameters(redacted)"
    companion object {
        fun validate(period: Int, digits: Int, counter: Long) {
            require(period in 1..86400 && digits in 1..9 && counter >= 0 && counter < Long.MAX_VALUE) { "Invalid entry parameters" }
        }
    }
}

/** Complete entry field bag plus typed projections. Opaque extension values remain separate from
 * typed state; edits replace named fields on the original bag, not on a reconstructed DTO.
 * Destroy releases references; immutable strings/COW snapshots are not physically wipeable.
 */
class EntryRecord internal constructor(fields: EntryObject) {
    private var owned: EntryObject? = fields
    private fun value(): EntryObject = checkNotNull(owned) { "Entry record destroyed" }
    val parameters: EntryParameters
    private var metadataValue: EntryMetadata? = null
    val metadata: EntryMetadata get() = checkNotNull(metadataValue) { "Entry record destroyed" }
    /** Encoded payload stays opaque until the platform's vetted material codec opens it. */
    val encodedPayload: String get() = (value()["secret"] as EntryText).value

    init {
        val kindText = optionalText(fields, "type", "TOTP")
        val kind = EntryRecordKind.entries.firstOrNull { it.name == kindText } ?: throw IllegalArgumentException()
        val algorithmText = optionalText(fields, "algorithm", "SHA1")
        val algorithm = EntryRecordAlgorithm.entries.firstOrNull { it.name == algorithmText } ?: throw IllegalArgumentException()
        val period = if (kind == EntryRecordKind.MOTP) 30 else integer(fields, "period", 30).also { require(it in 1..86400) }.toInt()
        val digits = integer(fields, "digits", if (kind == EntryRecordKind.STEAM) 5 else 6).also { require(it in 1..9) }.toInt()
        if (kind == EntryRecordKind.HOTP) require(fields["counter"] != null)
        val counter = integer(fields, "counter", 0)
        parameters = EntryParameters(kind, algorithm, period, digits, counter)
        val tags = when (val raw = fields["tags"]) {
            null -> emptyList()
            is EntryArray -> raw.values.map { (it as? EntryText)?.value ?: throw IllegalArgumentException() }
            else -> throw IllegalArgumentException()
        }
        metadataValue = EntryMetadata(optionalText(fields, "issuer", ""), requiredText(fields, "label"),
            optionalText(fields, "hidden_label", ""), tags, usage(fields["last_used"]), usage(fields["used_frequency"]),
            (fields["details_visible"] as? EntryBoolean)?.value == true)
        require(metadata.validationError() == null)
        val secret = requiredText(fields, "secret")
        val limit = when (kind) {
            EntryRecordKind.SSH_KEY -> 65544
            EntryRecordKind.PASSWORD, EntryRecordKind.PASSWORD_ARCHIVE -> 4 * 1024 * 1024
            EntryRecordKind.EMAIL -> 128 * 1024
            else -> 2048
        }
        validateBase32(secret, limit)
        if (kind in listOf(EntryRecordKind.NOTE, EntryRecordKind.SSH_KEY, EntryRecordKind.PASSWORD, EntryRecordKind.PASSWORD_ARCHIVE)) require(metadata.label.isNotBlank())
        if (kind == EntryRecordKind.MOTP) require(optionalText(fields, "pin", "").length <= EntryMetadata.MAX_TAG_CHARS)
        if (kind == EntryRecordKind.SSH_KEY) {
            require(optionalText(fields, "ssh_host", "").length <= 255)
            require(optionalText(fields, "ssh_username", "").length <= 128)
            require(optionalText(fields, "ssh_password", "").length <= 256)
            if (fields["ssh_port"] != null) require(integer(fields, "ssh_port", 0) in 1..65535)
        }
    }

    fun field(name: String): EntryValue? = value()[name]
    fun fields(): Map<String, EntryValue> = value().fields
    /** Strictly validates the resulting record before publication, preserving every other field. */
    fun replacing(name: String, replacement: EntryValue): EntryRecord {
        val candidate = EntryObject(value().fields + (name to replacement))
        val document = EntryDocumentCodec().decode("[" + candidate.json() + "]").document
            ?: throw IllegalArgumentException("Invalid replacement")
        return try { EntryRecord(EntryObject(document.entries.single().fields())) } finally { document.destroy() }
    }
    fun json(): String? = owned?.json()
    /** Native engines consume the shared typed interpretation, not their own coercion defaults.
     * Only present known fields are normalized. Unknown fields and omitted defaults stay intact.
     */
    fun extensionFieldsJson(): Map<String, String> = value().fields.filterKeys { it !in KNOWN_FIELDS }.mapValues { it.value.json() }
    fun materialJson(): String? = normalizedFields()?.let { normalized ->
        val fields = normalized.filterKeys { name -> name in KNOWN_FIELDS }.toMutableMap()
        val thumbnail = fields["thumbnail"] as? EntryText
        if (thumbnail == null || thumbnail.value.length > EntryMetadata.MAX_TEXT_CHARS) fields.remove("thumbnail")
        if (parameters.kind != EntryRecordKind.MOTP) fields.remove("pin")
        if (parameters.kind != EntryRecordKind.SSH_KEY) {
            for (name in listOf("ssh_host", "ssh_port", "ssh_username", "ssh_password")) fields.remove(name)
        }
        EntryObject(fields).json()
    }
    internal fun transferJson(): String? = normalizedFields()?.let { EntryObject(it).json() }
    private fun normalizedFields(): Map<String, EntryValue>? {
        val source = owned ?: return null
        val fields = source.fields.toMutableMap()
        for ((name, value) in listOf("digits" to parameters.digits.toLong(), "counter" to parameters.counter,
                "period" to parameters.period.toLong(), "last_used" to metadata.lastUsed, "used_frequency" to metadata.usedFrequency)) {
            if (fields.containsKey(name)) fields[name] = EntryNumber(value.toString())
        }
        if (fields.containsKey("details_visible")) fields["details_visible"] = EntryBoolean(metadata.detailsVisible)
        return fields
    }
    fun destroy() { owned = null; metadataValue = null }
    override fun toString() = "EntryRecord(redacted)"

    companion object {
        private val INTEGER = Regex("-?[0-9]+")
        private val KNOWN_FIELDS = setOf("secret", "issuer", "label", "period", "counter", "digits", "type",
            "algorithm", "tags", "thumbnail", "last_used", "used_frequency", "hidden_label", "pin",
            "details_visible", "ssh_host", "ssh_port", "ssh_username", "ssh_password")
        private fun requiredText(fields: EntryObject, name: String): String = (fields[name] as? EntryText)?.value ?: throw IllegalArgumentException()
        private fun optionalText(fields: EntryObject, name: String, fallback: String) = if (fields[name] == null) fallback else requiredText(fields, name)
        // Numeric strings are accepted by historical JVM documents. The record retains their JSON
        // type; adapters can still impose their engine's stricter material validation.
        private fun integer(fields: EntryObject, name: String, fallback: Long): Long =
            if (fields[name] == null) fallback else integerValue(fields[name]) ?: throw IllegalArgumentException()
        private fun integerValue(value: EntryValue?): Long? {
            val text = when (value) { is EntryNumber -> value.decimal; is EntryText -> value.value; else -> return null }
            if (!INTEGER.matches(text)) return null
            return text.toLongOrNull()
        }
        private fun usage(value: EntryValue?) = integerValue(value) ?: 0L
        private fun validateBase32(text: String, limit: Int) {
            require(text.isNotEmpty() && text.length <= limit * 2)
            val raw = text.uppercase()
            val body = raw.trimEnd('=')
            val padding = raw.length - body.length
            val remainder = body.length % 8
            require(remainder in listOf(0, 2, 4, 5, 7))
            val expectedPadding = (8 - remainder) % 8
            require(padding == 0 || padding == expectedPadding)
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            require(body.all { it in alphabet })
            require(body.length.toLong() * 5 / 8 in 1..limit.toLong())
            val unused = body.length * 5 % 8
            require(unused == 0 || alphabet.indexOf(body.last()) and ((1 shl unused) - 1) == 0)
        }
    }
}
