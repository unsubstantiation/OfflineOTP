package org.shadowice.flocke.andotp.Database

import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.PasswordMaterial
import org.shadowice.flocke.andotp.Utilities.OtpHashAlgorithm
import org.shadowice.flocke.andotp.Utilities.OtpKind
import org.shadowice.flocke.andotp.Utilities.SshConnection
import org.shadowice.flocke.andotp.Utilities.SshKeyMaterial
import org.shadowice.flocke.andotp.Utilities.EmailMaterial

import java.util.Collections

/**
 * Why an entry document was rejected. The values carry no document text, field values or seeds.
 *
 * `INVALID_TEXT` covers a required text field that is missing, JSON null or not a string;
 * `INVALID_NUMBER` covers a numeric field that is not a plain integer or does not fit;
 * `INVALID_PARAMETERS` covers numbers that are well-formed but outside the entry bounds.
 */
enum class EntryJsonError {
    INVALID_TEXT, TEXT_TOO_LONG, MISSING_SECRET, INVALID_SECRET, INVALID_NUMBER,
    MISSING_COUNTER, INVALID_PARAMETERS, UNSUPPORTED_TYPE, INVALID_ALGORITHM,
    INVALID_TAGS, TOO_MANY_TAGS
}

/** Closed result of parsing a single entry JSON object. */
sealed class EntryJsonResult {
    class Success(val value: ValidatedEntry) : EntryJsonResult() {
        override fun toString(): String = "EntryJsonResult.Success(redacted)"
    }
    data class Failure(val error: EntryJsonError) : EntryJsonResult()
}

/**
 * Validated entry metadata and owned seed read from an untrusted JSON document.
 *
 * Not a data class so secrets are never exposed via generated `toString`/`equals`/`copy`.
 * Stored documents encode all seeds (including mOTP) as Base32.
 */
class ValidatedEntry private constructor(
    val kind: OtpKind,
    secret: ByteArray,
    val period: Int,
    val digits: Int,
    val counter: Long,
    val issuer: String,
    val label: String,
    val algorithm: OtpHashAlgorithm,
    val thumbnailName: String?,
    val lastUsed: Long,
    val usedFrequency: Long,
    tags: List<String>,
    /** The mOTP PIN, empty for every other kind and for documents written before it was stored. */
    val pin: String,
    val detailsVisible: Boolean,
    /**
     * Optional SSH connection metadata, null for every other kind and for SSH documents written
     * before these fields existed.
     */
    val sshConnection: SshConnection.Details?
) : AutoCloseable {
    private val seed: ByteArray = secret.also {
        val limit = when (kind) {
            OtpKind.SSH_KEY -> SshKeyMaterial.MAX_ENCODED_BYTES
            OtpKind.PASSWORD, OtpKind.PASSWORD_ARCHIVE -> PasswordMaterial.MAX_BYTES
            OtpKind.EMAIL -> EmailMaterial.MAX_BYTES
            else -> InputPolicy.MAX_SECRET_BYTES
        }
        require(it.isNotEmpty() && it.size <= limit)
    }.copyOf()
    val tags: List<String> = Collections.unmodifiableList(ArrayList(tags))
    private var closed = false

    init {
        try {
            // Validate owned snapshots, not mutable caller storage after copying it.
            InputPolicy.tokenParameters(period, digits, counter)
            if (kind == OtpKind.SSH_KEY) {
                SshKeyMaterial.decode(seed)
                require(label.isNotBlank())
            } else {
                require(sshConnection == null)
            }
            if (kind == OtpKind.PASSWORD) PasswordMaterial.decode(seed)
            if (kind == OtpKind.EMAIL) EmailMaterial.decode(seed)
            if (kind == OtpKind.PASSWORD || kind == OtpKind.PASSWORD_ARCHIVE) require(label.isNotBlank())
            if (kind == OtpKind.NOTE) {
                InputPolicy.noteFromBytes(seed)
                require(label.isNotBlank())
            }
            require(kind != OtpKind.STEAM || digits == 5)
            InputPolicy.text(issuer, InputPolicy.MAX_TEXT_CHARS)
            InputPolicy.text(label, InputPolicy.MAX_TEXT_CHARS)
            InputPolicy.text(pin, InputPolicy.MAX_TAG_CHARS)
            require(this.tags.size <= InputPolicy.MAX_TAGS)
            this.tags.forEach { InputPolicy.text(requireNotNull(it), InputPolicy.MAX_TAG_CHARS) }
        } catch (invalid: IllegalArgumentException) {
            seed.fill(0)
            throw invalid
        }
    }

    @Synchronized
    fun copySecret(): ByteArray {
        check(!closed) { "Entry value already closed" }
        return seed.copyOf()
    }

    @Synchronized
    override fun close() {
        seed.fill(0)
        closed = true
    }

    override fun toString(): String = "ValidatedEntry(redacted)"

    companion object {
        // Only the document boundary needs this builder; the constructor rechecks invariants too.
        @JvmSynthetic
        internal fun create(
            kind: OtpKind, secret: ByteArray, period: Int, digits: Int, counter: Long,
            issuer: String, label: String, algorithm: OtpHashAlgorithm, thumbnailName: String?,
            lastUsed: Long, usedFrequency: Long, tags: List<String>, pin: String, detailsVisible: Boolean,
            sshConnection: SshConnection.Details?
        ): ValidatedEntry = ValidatedEntry(kind, secret, period, digits, counter, issuer, label,
            algorithm, thumbnailName, lastUsed, usedFrequency, tags, pin, detailsVisible, sshConnection)
    }
}
