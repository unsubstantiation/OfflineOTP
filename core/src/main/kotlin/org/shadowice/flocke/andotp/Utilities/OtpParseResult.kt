package org.shadowice.flocke.andotp.Utilities

import java.util.Collections

/** No input strings, seeds, or provider exception messages in error values. */
enum class OtpParseError {
    INPUT_TOO_LONG, INVALID_URI, UNSUPPORTED_TYPE, DUPLICATE_PARAMETER,
    MISSING_SECRET, INVALID_SECRET, MISSING_COUNTER, INVALID_NUMBER,
    INVALID_PARAMETERS, INVALID_ALGORITHM, TEXT_TOO_LONG, TOO_MANY_TAGS
}

enum class OtpKind { TOTP, HOTP, MOTP, STEAM, NOTE, SSH_KEY, PASSWORD, PASSWORD_ARCHIVE, EMAIL }
enum class OtpHashAlgorithm { SHA1, SHA256, SHA512 }

/** Closed result of parsing an `otpauth://` URI. */
sealed class OtpParseResult {
    class Success(val value: ValidatedOtp) : OtpParseResult() {
        override fun toString(): String = "OtpParseResult.Success(redacted)"
    }
    data class Failure(val error: OtpParseError) : OtpParseResult()
}

/**
 * Immutable metadata and an owned seed; deliberately NOT a data class.
 * No seed-based equality/hash, generated copy/component methods or serialization.
 * Consumers copy the seed into their own model, then close this value best-effort.
 */
class ValidatedOtp private constructor(
    val kind: OtpKind,
    secret: ByteArray,
    val period: Int,
    val digits: Int,
    val counter: Long,
    val issuer: String,
    val label: String,
    val algorithm: OtpHashAlgorithm,
    tags: List<String>
) : AutoCloseable {
    private val seed: ByteArray = secret.also {
        require(it.isNotEmpty() && it.size <= InputPolicy.MAX_SECRET_BYTES)
    }.copyOf()
    val tags: List<String> = Collections.unmodifiableList(ArrayList(tags))
    private var closed = false

    init {
        try {
            // A standalone note is never an otpauth URI or a validated OTP.
            require(kind in listOf(OtpKind.TOTP, OtpKind.HOTP, OtpKind.MOTP, OtpKind.STEAM))
            // Validate owned snapshots, not mutable caller storage after copying it.
            require(kind != OtpKind.MOTP || (seed.size % 2 == 0 && seed.all {
                val c = it.toInt()
                c in 48..57 || c in 65..70 || c in 97..102
            }))
            InputPolicy.tokenParameters(period, digits, counter)
            require(kind != OtpKind.STEAM || digits == 5)
            InputPolicy.text(issuer, InputPolicy.MAX_TEXT_CHARS)
            InputPolicy.text(label, InputPolicy.MAX_TEXT_CHARS)
            require(this.tags.size <= InputPolicy.MAX_TAGS)
            this.tags.forEach { InputPolicy.text(requireNotNull(it), InputPolicy.MAX_TAG_CHARS) }
        } catch (invalid: IllegalArgumentException) {
            seed.fill(0)
            throw invalid
        }
    }

    @Synchronized
    fun copySecret(): ByteArray {
        check(!closed) { "OTP value already closed" }
        return seed.copyOf()
    }

    @Synchronized
    override fun close() {
        seed.fill(0)
        closed = true
    }

    override fun toString(): String = "ValidatedOtp(redacted)"

    companion object {
        // Only the parser needs this builder; constructor rechecks invariants too. Kept
        // @JvmSynthetic: it is part of the Kotlin-only core API, not the Java-visible surface.
        @JvmSynthetic
        fun create(
            kind: OtpKind, secret: ByteArray, period: Int, digits: Int, counter: Long,
            issuer: String, label: String, algorithm: OtpHashAlgorithm, tags: List<String>
        ): ValidatedOtp = ValidatedOtp(kind, secret, period, digits, counter, issuer, label, algorithm, tags)
    }
}
