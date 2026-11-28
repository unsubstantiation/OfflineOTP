package com.fairuniversal.offlineotp.domain

/** No input strings, seeds, or provider exception messages in error values. */
enum class ProvisioningError {
    INPUT_TOO_LONG, INVALID_URI, UNSUPPORTED_TYPE, DUPLICATE_PARAMETER,
    MISSING_SECRET, INVALID_SECRET, MISSING_COUNTER, INVALID_NUMBER,
    INVALID_PARAMETERS, INVALID_ALGORITHM, TEXT_TOO_LONG, TOO_MANY_TAGS
}


/** Closed result of parsing an `otpauth://` URI. */
sealed class ProvisioningResult {
    class Success(val value: PortableOtp) : ProvisioningResult() {
        override fun toString(): String = "ProvisioningResult.Success(redacted)"
    }
    data class Failure(val error: ProvisioningError) : ProvisioningResult()
}

/** Owned seed with explicit best-effort disposal; metadata and diagnostics never print material. */
class PortableOtp(val kind: EntryRecordKind, secret: ByteArray, val period: Int, val digits: Int,
    val counter: Long, val issuer: String, val label: String, val algorithm: EntryRecordAlgorithm, tags: List<String>) {
    private var seed: ByteArray? = secret.also { require(it.isNotEmpty() && it.size <= InterchangeLimits.MAX_SECRET_BYTES) }.copyOf()
    val tags = tags.toList()
    init {
        try {
            require(kind in listOf(EntryRecordKind.TOTP, EntryRecordKind.HOTP, EntryRecordKind.MOTP, EntryRecordKind.STEAM))
            require(secret.isNotEmpty() && secret.size <= InterchangeLimits.MAX_SECRET_BYTES)
            EntryParameters.validate(period, digits, counter)
            require(kind != EntryRecordKind.STEAM || digits == 5)
            require(issuer.length <= InterchangeLimits.MAX_TEXT_CHARS && label.length <= InterchangeLimits.MAX_TEXT_CHARS)
            require(tags.size <= InterchangeLimits.MAX_TAGS && tags.all { it.length <= InterchangeLimits.MAX_TAG_CHARS })
        } catch (e: IllegalArgumentException) { destroy(); throw e }
    }
    fun copySecret(): ByteArray = checkNotNull(seed).copyOf()
    fun destroy() { seed?.fill(0); seed = null }
    override fun toString() = "PortableOtp(redacted)"
    companion object {
        fun create(kind: EntryRecordKind, secret: ByteArray, period: Int, digits: Int, counter: Long,
            issuer: String, label: String, algorithm: EntryRecordAlgorithm, tags: List<String>) =
            PortableOtp(kind, secret, period, digits, counter, issuer, label, algorithm, tags)
    }
}
internal object InterchangeLimits {
    const val MAX_URI_CHARS = 16384
    const val MAX_ENTRIES = 2000
    const val MAX_TEXT_CHARS = EntryMetadata.MAX_TEXT_CHARS
    const val MAX_TAGS = EntryMetadata.MAX_TAGS
    const val MAX_TAG_CHARS = EntryMetadata.MAX_TAG_CHARS
    const val MAX_SECRET_BYTES = 2048
    const val MAX_PERIOD_SECONDS = 86400
}
