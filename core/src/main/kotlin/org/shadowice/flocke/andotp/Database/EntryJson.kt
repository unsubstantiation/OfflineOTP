package org.shadowice.flocke.andotp.Database

import org.apache.commons.codec.binary.Base32
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.PasswordMaterial
import org.shadowice.flocke.andotp.Utilities.OtpHashAlgorithm
import org.shadowice.flocke.andotp.Utilities.OtpKind
import org.shadowice.flocke.andotp.Utilities.SshKeyMaterial
import org.shadowice.flocke.andotp.Utilities.EmailMaterial
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

import java.util.Locale

/**
 * Android-free JSON parser and serializer for vault and backup entries.
 *
 * [parse] returns an [EntryJsonResult.Failure] on malformed input rather than throwing, and
 * [serialize] writes fields in the canonical stored order.
 */
object EntryJson {
    private const val JSON_SECRET = "secret"
    private const val JSON_ISSUER = "issuer"
    private const val JSON_LABEL = "label"
    private const val JSON_PERIOD = "period"
    private const val JSON_COUNTER = "counter"
    private const val JSON_DIGITS = "digits"
    private const val JSON_TYPE = "type"
    private const val JSON_ALGORITHM = "algorithm"
    private const val JSON_TAGS = "tags"
    private const val JSON_THUMBNAIL = "thumbnail"
    private const val JSON_LAST_USED = "last_used"
    private const val JSON_USED_FREQUENCY = "used_frequency"
    private const val JSON_PIN = "pin"
    private const val JSON_DETAILS_VISIBLE = "details_visible"

    private const val DEFAULT_PERIOD = TokenCalculator.TOTP_DEFAULT_PERIOD
    private val INTEGER_TEXT = Regex("-?[0-9]+")
    private val BASE32_TEXT = Regex("[A-Z2-7]+={0,6}")

    /** Validates one untrusted entry object. The returned value owns its own seed copy. */
    @JvmStatic
    fun parse(json: JSONObject): EntryJsonResult {
        return try {
            EntryJsonResult.Success(parsed(json))
        } catch (invalid: Rejected) {
            EntryJsonResult.Failure(invalid.error)
        }
    }

    private fun parsed(json: JSONObject): ValidatedEntry {
        if (!json.has(JSON_SECRET)) reject(EntryJsonError.MISSING_SECRET)
        val kind = if (json.has(JSON_TYPE)) kind(json) else OtpKind.TOTP
        val limit = when (kind) {
            OtpKind.SSH_KEY -> SshKeyMaterial.MAX_ENCODED_BYTES
            OtpKind.PASSWORD, OtpKind.PASSWORD_ARCHIVE -> PasswordMaterial.MAX_BYTES
            OtpKind.EMAIL -> EmailMaterial.MAX_BYTES
            else -> InputPolicy.MAX_SECRET_BYTES
        }
        val seed = decodeBase32Secret(text(json, JSON_SECRET), limit) ?: reject(EntryJsonError.INVALID_SECRET)
        try {
            val label = text(json, JSON_LABEL)
            // Older backups did not store the issuer separately.
            val issuer = if (json.has(JSON_ISSUER)) text(json, JSON_ISSUER) else ""
            if (kind == OtpKind.SSH_KEY) {
                try { SshKeyMaterial.decode(seed) } catch (_: IllegalArgumentException) {
                    reject(EntryJsonError.INVALID_TEXT)
                }
                if (label.isBlank()) reject(EntryJsonError.INVALID_TEXT)
            }
            if (kind == OtpKind.PASSWORD) {
                try { PasswordMaterial.decode(seed) } catch (_: IllegalArgumentException) { reject(EntryJsonError.INVALID_TEXT) }
            }
            if (kind == OtpKind.EMAIL) {
                try { EmailMaterial.decode(seed) } catch (_: Exception) { reject(EntryJsonError.INVALID_TEXT) }
            }
            if ((kind == OtpKind.PASSWORD || kind == OtpKind.PASSWORD_ARCHIVE) && label.isBlank()) reject(EntryJsonError.INVALID_TEXT)
            if (kind == OtpKind.NOTE) {
                try { InputPolicy.noteFromBytes(seed) } catch (_: IllegalArgumentException) {
                    reject(EntryJsonError.INVALID_TEXT)
                }
                if (label.isBlank()) reject(EntryJsonError.INVALID_TEXT)
            }
            // mOTP always rolls over on TokenCalculator.MOTP_STEP_SECONDS (see Entry.getTokenStepSeconds);
            // the stored period never drives its timing, so a period field on an mOTP document (never
            // written by serialize(), never produced by the otpauth:// parser) is ignored rather than
            // surfaced as a misleading "custom period" value.
            val period = if (kind == OtpKind.MOTP) DEFAULT_PERIOD
                else if (json.has(JSON_PERIOD)) int(json, JSON_PERIOD) else DEFAULT_PERIOD
            if (kind == OtpKind.HOTP && !json.has(JSON_COUNTER)) reject(EntryJsonError.MISSING_COUNTER)
            val counter = if (json.has(JSON_COUNTER)) number(json, JSON_COUNTER) else 0L
            val digits = if (json.has(JSON_DIGITS)) int(json, JSON_DIGITS)
                else if (kind == OtpKind.STEAM) TokenCalculator.STEAM_DEFAULT_DIGITS
                else TokenCalculator.TOTP_DEFAULT_DIGITS
            val algorithm = if (json.has(JSON_ALGORITHM)) algorithm(json) else OtpHashAlgorithm.SHA1
            val tags = tags(json)
            val thumbnailName = thumbnailName(json)
            val lastUsed = usage(json, JSON_LAST_USED)
            val usedFrequency = usage(json, JSON_USED_FREQUENCY)
            // Missing, malformed or unrecognized values are hidden, not revealed.
            val detailsVisible = json.opt(JSON_DETAILS_VISIBLE) == true
            // Absent on non-mOTP documents and older mOTP documents; defaults to empty. Also forced
            // empty for every non-mOTP kind, matching the field serialize() only ever writes for mOTP.
            val pin = if (kind == OtpKind.MOTP && json.has(JSON_PIN)) text(json, JSON_PIN) else ""

            if (seed.isEmpty() || seed.size > limit) reject(EntryJsonError.INVALID_SECRET)
            try {
                InputPolicy.tokenParameters(period, digits, counter)
            } catch (invalid: IllegalArgumentException) {
                reject(EntryJsonError.INVALID_PARAMETERS)
            }
            if (kind == OtpKind.STEAM && digits != TokenCalculator.STEAM_DEFAULT_DIGITS)
                reject(EntryJsonError.INVALID_PARAMETERS)
            bounded(issuer, InputPolicy.MAX_TEXT_CHARS)
            bounded(label, InputPolicy.MAX_TEXT_CHARS)
            bounded(pin, InputPolicy.MAX_TAG_CHARS)
            if (tags.size > InputPolicy.MAX_TAGS) reject(EntryJsonError.TOO_MANY_TAGS)
            for (tag in tags) bounded(tag, InputPolicy.MAX_TAG_CHARS)

            return ValidatedEntry.create(kind, seed, period, digits, counter, issuer, label, algorithm,
                thumbnailName, lastUsed, usedFrequency, tags, pin, detailsVisible)
        } catch (invalid: Rejected) {
            seed.fill(0)
            throw invalid
        } catch (invalid: IllegalArgumentException) {
            // ValidatedEntry re-checks parameter bounds.
            seed.fill(0)
            throw Rejected(EntryJsonError.INVALID_PARAMETERS)
        }
    }

    /**
     * Writes one entry in the stored format. The field order is part of that format: the vault
     * fixtures and the JSON round-trip test compare the serialized text.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(JSONException::class)
    fun serialize(kind: OtpKind, secret: ByteArray, issuer: String, label: String, digits: Int,
                  period: Int, counter: Long, algorithm: OtpHashAlgorithm, thumbnailName: String,
                  lastUsed: Long, usedFrequency: Long, tags: List<String>, pin: String,
                  detailsVisible: Boolean = false): JSONObject {
        val jsonObj = JSONObject()
        jsonObj.put(JSON_SECRET, String(Base32().encode(secret)))
        jsonObj.put(JSON_ISSUER, issuer)
        jsonObj.put(JSON_LABEL, label)
        jsonObj.put(JSON_DIGITS, digits)
        jsonObj.put(JSON_TYPE, kind.name)
        jsonObj.put(JSON_ALGORITHM, algorithm.name)
        jsonObj.put(JSON_THUMBNAIL, thumbnailName)
        jsonObj.put(JSON_LAST_USED, lastUsed)
        jsonObj.put(JSON_USED_FREQUENCY, usedFrequency)

        if (kind == OtpKind.TOTP)
            jsonObj.put(JSON_PERIOD, period)
        else if (kind == OtpKind.STEAM && period != DEFAULT_PERIOD)
            // Only a non-default Steam period is written, so default Steam documents stay byte-identical.
            jsonObj.put(JSON_PERIOD, period)
        else if (kind == OtpKind.HOTP)
            jsonObj.put(JSON_COUNTER, counter)

        // Only write a non-empty mOTP PIN so other documents keep their exact field set.
        if (kind == OtpKind.MOTP && pin.isNotEmpty())
            jsonObj.put(JSON_PIN, pin)

        val tagsArray = JSONArray()
        for (tag in tags) {
            tagsArray.put(tag)
        }
        jsonObj.put(JSON_TAGS, tagsArray)
        // Preserve legacy bytes for the default (concealed) state.
        if (detailsVisible) jsonObj.put(JSON_DETAILS_VISIBLE, true)

        return jsonObj
    }

    /**
     * Decodes the canonical padded/unpadded Base32 text a seed is stored as, or returns null when
     * the text is not a usable seed. Shared with [Entry]'s manual-entry path and its secret check.
     */
    @JvmStatic
    fun decodeBase32Secret(encoded: String?): ByteArray? =
        decodeBase32Secret(encoded, InputPolicy.MAX_SECRET_BYTES)

    private fun decodeBase32Secret(encoded: String?, limit: Int): ByteArray? {
        if (encoded == null || encoded.length > limit * 2) return null
        val normalized = encoded.uppercase(Locale.ROOT)
        if (!BASE32_TEXT.matches(normalized)) return null
        val codec = Base32()
        val bytes = codec.decode(normalized)
        val canonical = codec.encodeAsString(bytes)
        if (!(normalized == canonical || normalized == canonical.replace("=", ""))) return null
        return bytes
    }

    /** A required text field: missing keys, JSON null and non-string values all reject. */
    private fun text(json: JSONObject, name: String): String {
        val value = try {
            json.get(name)
        } catch (missing: JSONException) {
            reject(EntryJsonError.INVALID_TEXT)
        }
        return value as? String ?: reject(EntryJsonError.INVALID_TEXT)
    }

    /** Parses an integer field (optional leading '-', ASCII digits only, no float coercion). */
    private fun number(json: JSONObject, name: String): Long {
        val value = try {
            json.get(name)
        } catch (missing: JSONException) {
            reject(EntryJsonError.INVALID_NUMBER)
        }
        val text = value.toString()
        if (!INTEGER_TEXT.matches(text)) reject(EntryJsonError.INVALID_NUMBER)
        return text.toLongOrNull() ?: reject(EntryJsonError.INVALID_NUMBER)
    }

    /** Parses a numeric field that must fit in a 32-bit signed integer. */
    private fun int(json: JSONObject, name: String): Int {
        val value = number(json, name)
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) reject(EntryJsonError.INVALID_NUMBER)
        return value.toInt()
    }

    private fun kind(json: JSONObject): OtpKind {
        return when (val name = text(json, JSON_TYPE)) {
            "TOTP" -> OtpKind.TOTP
            "HOTP" -> OtpKind.HOTP
            "MOTP" -> OtpKind.MOTP
            "STEAM" -> OtpKind.STEAM
            "NOTE" -> OtpKind.NOTE
            "SSH_KEY" -> OtpKind.SSH_KEY
            "PASSWORD" -> OtpKind.PASSWORD
            "PASSWORD_ARCHIVE" -> OtpKind.PASSWORD_ARCHIVE
            "EMAIL" -> OtpKind.EMAIL
            else -> reject(EntryJsonError.UNSUPPORTED_TYPE)
        }
    }

    private fun algorithm(json: JSONObject): OtpHashAlgorithm {
        val name = text(json, JSON_ALGORITHM)
        return OtpHashAlgorithm.entries.firstOrNull { it.name == name } ?: reject(EntryJsonError.INVALID_ALGORITHM)
    }

    private fun tags(json: JSONObject): List<String> {
        if (!json.has(JSON_TAGS)) return emptyList()
        val array = try {
            json.getJSONArray(JSON_TAGS)
        } catch (invalid: JSONException) {
            reject(EntryJsonError.INVALID_TAGS)
        }
        if (array.length() > InputPolicy.MAX_TAGS) reject(EntryJsonError.TOO_MANY_TAGS)
        val tags = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val tag = try {
                array.get(index)
            } catch (invalid: JSONException) {
                reject(EntryJsonError.INVALID_TAGS)
            }
            if (tag !is String) reject(EntryJsonError.INVALID_TAGS)
            tags.add(tag)
        }
        return tags
    }

    /** Best-effort thumbnail name: returns null when missing, unreadable, or oversized. */
    private fun thumbnailName(json: JSONObject): String? {
        val value = try {
            json.getString(JSON_THUMBNAIL)
        } catch (invalid: Exception) {
            return null
        }
        return if (value.length > InputPolicy.MAX_TEXT_CHARS) null else value
    }

    /** Best-effort usage metadata: missing or invalid values default to 0. */
    private fun usage(json: JSONObject, name: String): Long {
        return try {
            json.getLong(name)
        } catch (invalid: Exception) {
            0L
        }
    }

    private fun bounded(value: String, limit: Int) {
        if (value.length > limit) reject(EntryJsonError.TEXT_TOO_LONG)
    }

    private class Rejected(val error: EntryJsonError) : IllegalArgumentException(error.name)
    private fun reject(error: EntryJsonError): Nothing = throw Rejected(error)
}
