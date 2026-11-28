package com.fairuniversal.offlineotp.domain

/** First portable entry slice. Seed/key material, kind-specific fields and opaque extensions stay in the
 * existing engine record. Never rebuild an entire record from this deliberately partial model.
 * Usage values are signed 64-bit legacy values; decoding adapters retain their existing defaults.
 */
class EntryMetadata(
    val issuer: String,
    val label: String,
    val hiddenLabel: String,
    tags: List<String>,
    val lastUsed: Long,
    val usedFrequency: Long,
    val detailsVisible: Boolean
) {
    private val ownedTags = tags.toList()
    // A returned collection cannot mutate the model, even through a JVM mutable-list cast.
    val tags: List<String> get() = ownedTags.toList()

    fun validationError(): EntryMetadataError? = when {
        issuer.length > MAX_TEXT_CHARS || label.length > MAX_TEXT_CHARS || hiddenLabel.length > MAX_TEXT_CHARS -> EntryMetadataError.TEXT_TOO_LONG
        ownedTags.size > MAX_TAGS -> EntryMetadataError.TOO_MANY_TAGS
        ownedTags.any { it.length > MAX_TAG_CHARS } -> EntryMetadataError.TEXT_TOO_LONG
        else -> null
    }

    /** Metadata-only JSON patch; adapters overlay it on the original entry, never replace it.
     * No number conversion through Double: counters/timestamps above 2^53 remain exact.
     * No trimming, tag deduplication, reordering, or Unicode normalization.
     */
    fun toJson(): String {
        require(validationError() == null) { "Invalid entry metadata" }
        return buildString {
            append("{\"issuer\":"); append(quote(issuer))
            append(",\"label\":"); append(quote(label))
            append(",\"last_used\":"); append(lastUsed)
            append(",\"used_frequency\":"); append(usedFrequency)
            append(",\"tags\":[")
            ownedTags.forEachIndexed { index, tag -> if (index != 0) append(','); append(quote(tag)) }
            append(']')
            if (hiddenLabel.isNotEmpty()) { append(",\"hidden_label\":"); append(quote(hiddenLabel)) }
            if (detailsVisible) append(",\"details_visible\":true")
            append('}')
        }
    }

    override fun toString(): String = "EntryMetadata(redacted)"

    companion object {
        const val MAX_TEXT_CHARS = 1024
        const val MAX_TAGS = 32
        const val MAX_TAG_CHARS = 128

        private fun quote(text: String): String = EntryJsonText.quote(text)
    }
}

enum class EntryMetadataError { TEXT_TOO_LONG, TOO_MANY_TAGS }
