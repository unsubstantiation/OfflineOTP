/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 * Copyright (C) 2015 Bruno Bierbaumer
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Database

import android.net.Uri

import org.apache.commons.codec.binary.Base32
import org.apache.commons.codec.binary.Hex
import org.json.JSONException
import org.json.JSONObject
import org.shadowice.flocke.andotp.Utilities.EntryThumbnail
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.OtpHashAlgorithm
import org.shadowice.flocke.andotp.Utilities.OtpKind
import org.shadowice.flocke.andotp.Utilities.OtpParseResult
import org.shadowice.flocke.andotp.Utilities.OtpUriParser
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.Utilities.Tools
import org.shadowice.flocke.andotp.Utilities.ValidatedOtp

import java.nio.charset.StandardCharsets
import java.util.Objects

/** One OTP entry: its secret, type and display/sort metadata, serialized to JSON and otpauth:// URIs. */
/* The Java class was non-final and stayed extendable; Kotlin classes are final by default. */
open class Entry {
    /** The OTP algorithms supported by andOTP. */
    enum class OTPType {
        TOTP, HOTP, MOTP, STEAM
    }

    private var type: OTPType = OTPType.TOTP
    private var period: Int = TokenCalculator.TOTP_DEFAULT_PERIOD
    private var digits: Int = TokenCalculator.TOTP_DEFAULT_DIGITS
    private var algorithm: TokenCalculator.HashAlgorithm = TokenCalculator.DEFAULT_ALGORITHM
    private var secret: ByteArray? = null
    private var counter: Long = 0
    private var issuer: String = ""
    private var label: String = ""
    private var currentOTP: String? = null
    private var prevOTP: String? = null
    private var visible = false
    private var hideTask: Runnable? = null
    private var last_update = 0L
    private var hasUpdatedTimeToken = false
    private var last_used = 0L
    private var used_frequency = 0L

    /**
     * The entry's tags. Kept a directly assignable public field (and mirrored by
     * [getTags]/[setTags]) because the Java model exposed all three.
     */
    @JvmField
    var tags: MutableList<String> = ArrayList()

    private var thumbnail: EntryThumbnail.EntryThumbnails = EntryThumbnail.EntryThumbnails.Default
    private var color = COLOR_DEFAULT
    private var pin = ""
    private var listId = 0L

    constructor()

    constructor(type: OTPType, secret: String?, period: Int, digits: Int, issuer: String?, label: String?,
                algorithm: TokenCalculator.HashAlgorithm, tags: MutableList<String>) {
        this.type = type
        this.secret = decodeSecret(secret)
        this.period = period
        this.digits = digits
        this.issuer = issuer ?: ""
        this.label = label ?: ""
        this.algorithm = algorithm
        this.tags = tags
        setThumbnailFromIssuer(this.issuer)
        validate()
    }

    constructor(type: OTPType, secret: String?, counter: Long, digits: Int, issuer: String?, label: String?,
                algorithm: TokenCalculator.HashAlgorithm, tags: MutableList<String>) {
        this.type = type
        this.secret = decodeSecret(secret)
        this.counter = counter
        this.digits = digits
        this.issuer = issuer ?: ""
        this.label = label ?: ""
        this.algorithm = algorithm
        this.tags = tags
        setThumbnailFromIssuer(this.issuer)
        validate()
    }

    constructor(type: OTPType, secret: String, issuer: String?, label: String?,
                tags: MutableList<String>) {
        this.type = type
        this.secret = secret.toByteArray(StandardCharsets.UTF_8)
        this.issuer = issuer ?: ""
        this.label = label ?: ""
        this.tags = tags
        this.period = TokenCalculator.TOTP_DEFAULT_PERIOD
        setThumbnailFromIssuer(this.issuer)
        validate()
    }

    /** Legacy Java adapter: URI policy and explicit outcomes live in the Kotlin parser. */
    @Throws(Exception::class)
    constructor(contents: String?) {
        val result = OtpUriParser.parse(contents)
        if (result is OtpParseResult.Failure) {
            throw IllegalArgumentException("Invalid OTP URI: " + result.error.name)
        }
        val value = (result as OtpParseResult.Success).value
        try {
            this.type = OTPType.valueOf(value.kind.name)
            this.secret = value.copySecret()
            this.period = value.period
            this.digits = value.digits
            this.counter = value.counter
            this.issuer = value.issuer
            this.label = value.label
            this.algorithm = TokenCalculator.HashAlgorithm.valueOf(value.algorithm.name)
            this.tags = ArrayList(value.tags)
            setThumbnailFromIssuer(this.issuer)
            validate()
        } catch (error: RuntimeException) {
            destroy()
            throw error
        } finally {
            value.close()
        }
    }

    /** Legacy Java adapter: field policy and explicit outcomes live in the Kotlin document parser. */
    @Throws(Exception::class)
    constructor(jsonObj: JSONObject) {
        val result = EntryJson.parse(jsonObj)
        if (result is EntryJsonResult.Failure) {
            throw IllegalArgumentException("Invalid entry JSON: " + result.error.name)
        }
        val value = (result as EntryJsonResult.Success).value
        try {
            this.secret = value.copySecret()
            this.label = value.label
            this.issuer = value.issuer
            this.type = OTPType.valueOf(value.kind.name)
            this.period = value.period
            this.digits = value.digits
            this.counter = value.counter
            this.algorithm = TokenCalculator.HashAlgorithm.valueOf(value.algorithm.name)
            this.tags = ArrayList(value.tags)
            this.thumbnail = thumbnailFromName(value.thumbnailName)
            this.last_used = value.lastUsed
            this.used_frequency = value.usedFrequency
            this.pin = value.pin
            validate()
        } catch (error: RuntimeException) {
            destroy()
            throw error
        } finally {
            value.close()
        }
    }

    /**
     * Checks the entry's invariants: the seed bounds, the token parameters, the Steam digit count
     * and the text/tag bounds.
     */
    /* A Java caller can still put nulls into the tag list; the Java model rejected that. */
    @Suppress("SENSELESS_COMPARISON")
    fun validate() {
        val seed = secret
        if (seed == null || seed.isEmpty() || seed.size > InputPolicy.MAX_SECRET_BYTES)
            throw IllegalArgumentException("Invalid entry")
        InputPolicy.tokenParameters(period, digits, counter)
        if (type == OTPType.STEAM && digits != TokenCalculator.STEAM_DEFAULT_DIGITS)
            throw IllegalArgumentException("Invalid Steam digits")
        InputPolicy.text(issuer, InputPolicy.MAX_TEXT_CHARS)
        InputPolicy.text(label, InputPolicy.MAX_TEXT_CHARS)
        InputPolicy.text(pin, InputPolicy.MAX_TAG_CHARS)
        if (tags.size > InputPolicy.MAX_TAGS) throw IllegalArgumentException("Invalid tags")
        for (tag in tags) {
            if (tag == null) throw IllegalArgumentException("Invalid tag")
            InputPolicy.text(tag, InputPolicy.MAX_TAG_CHARS)
        }
    }

    /** Best-effort cleanup of owned arrays/references, including entries held by views. */
    fun destroy() {
        secret?.fill(0)
        secret = null
        invalidateCachedToken()
        pin = ""
        issuer = ""
        label = ""
        tags = ArrayList()
        visible = false
        hideTask = null
    }

    /** Serializes this entry to the JSON object used in the database and in backups. */
    @Throws(JSONException::class)
    fun toJSON(): JSONObject = EntryJson.serialize(
        kind = OtpKind.valueOf(type.name),
        secret = ownedSecret(),
        issuer = getIssuer(),
        label = getLabel(),
        digits = getDigits(),
        period = getPeriod(),
        counter = getCounter(),
        algorithm = OtpHashAlgorithm.valueOf(algorithm.name),
        thumbnailName = getThumbnail().name,
        lastUsed = getLastUsed(),
        usedFrequency = getUsedFrequency(),
        tags = tags,
        pin = getPin()
    )

    /** Serializes this entry to an otpauth:// URI (null if the type is unsupported). */
    fun toUri(): Uri? {
        val typeName = when (type) {
            OTPType.TOTP -> "totp"
            OTPType.HOTP -> "hotp"
            OTPType.STEAM -> "steam"
            OTPType.MOTP -> "motp"
        }
        val builder = Uri.Builder()
            .scheme("otpauth")
            .authority(typeName)
            .appendPath(label)

        if (type == OTPType.MOTP)
            builder.appendQueryParameter("secret", String(ownedSecret()))
        else
            builder.appendQueryParameter("secret", Base32().encodeAsString(ownedSecret()))

        builder.appendQueryParameter("issuer", issuer)

        /* The Java switch fell through from HOTP into the period check: a counter-based entry with
           a non-default period still emitted its period. Both branches keep that behaviour. */
        if (type == OTPType.HOTP) {
            builder.appendQueryParameter("counter", counter.toString())
        }
        // Steam URIs may carry a period; keep it so a re-import does not change the code timing.
        if (type == OTPType.HOTP || type == OTPType.TOTP || type == OTPType.STEAM) {
            if (period != TokenCalculator.TOTP_DEFAULT_PERIOD)
                builder.appendQueryParameter("period", period.toString())
        }
        if (digits != TokenCalculator.TOTP_DEFAULT_DIGITS) {
            builder.appendQueryParameter("digits", digits.toString())
        }
        if (algorithm != TokenCalculator.DEFAULT_ALGORITHM) {
            builder.appendQueryParameter("algorithm", algorithm.name)
        }
        for (tag in tags) {
            builder.appendQueryParameter("tags", tag)
        }
        return builder.build()
    }

    fun isTimeBased(): Boolean = type == OTPType.TOTP || type == OTPType.STEAM || type == OTPType.MOTP

    fun isCounterBased(): Boolean = type == OTPType.HOTP

    fun getType(): OTPType = type

    private fun invalidateCachedToken() {
        hasUpdatedTimeToken = false
        last_update = 0L
        currentOTP = ""
        prevOTP = ""
    }

    fun setType(type: OTPType) {
        if (this.type != type) invalidateCachedToken()
        this.type = type
    }

    fun getSecret(): ByteArray? = secret?.clone()

    /** The secret as stored/displayed: Base32, or the raw secret for mOTP. */
    fun getSecretEncoded(): String {
        val seed = ownedSecret()
        return if (type == OTPType.MOTP) String(seed) else String(Base32().encode(seed))
    }

    fun setSecret(secret: ByteArray?) {
        // Copy before wiping the old value in case the caller passes an aliased array.
        val replacement = secret?.clone()
        this.secret?.fill(0)
        this.secret = replacement
        invalidateCachedToken()
    }

    fun getIssuer(): String = issuer

    fun setIssuer(issuer: String?, updateThumbnail: Boolean) {
        this.issuer = issuer ?: ""

        if (updateThumbnail && issuer != null)
            setThumbnailFromIssuer(issuer)
        validate()
    }

    fun getLabel(): String = label

    fun setLabel(label: String?) {
        this.label = label ?: ""
    }

    fun getPeriod(): Int = period

    /**
     * Seconds per token step. mOTP always uses the protocol's 10-second step, so the stored
     * period (which is 30 for mOTP entries) must not drive its refresh, expiry or progress.
     */
    fun getTokenStepSeconds(): Int = if (type == OTPType.MOTP) TokenCalculator.MOTP_STEP_SECONDS else period

    fun setPeriod(period: Int) {
        if (this.period != period) invalidateCachedToken()
        this.period = period
    }

    fun getCounter(): Long = counter

    fun setCounter(counter: Long) {
        this.counter = counter
    }

    fun getDigits(): Int = digits

    fun setDigits(digits: Int) {
        if (this.digits != digits) invalidateCachedToken()
        this.digits = digits
    }

    fun getTags(): MutableList<String> = tags

    fun setTags(tags: MutableList<String>) {
        this.tags = tags
    }

    fun getThumbnail(): EntryThumbnail.EntryThumbnails = thumbnail

    fun setThumbnail(value: EntryThumbnail.EntryThumbnails) {
        thumbnail = value
    }

    fun getAlgorithm(): TokenCalculator.HashAlgorithm = algorithm

    fun hasNonDefaultPeriod(): Boolean = period != TokenCalculator.TOTP_DEFAULT_PERIOD

    fun isVisible(): Boolean = visible

    fun setVisible(value: Boolean) {
        visible = value
    }

    fun setHideTask(newTask: Runnable?) {
        hideTask = newTask
    }

    fun getHideTask(): Runnable? = hideTask

    fun getLastUsed(): Long = last_used

    fun setLastUsed(value: Long) {
        last_used = value
    }

    fun getUsedFrequency(): Long = used_frequency

    fun setUsedFrequency(value: Long) {
        used_frequency = value
    }

    fun getCurrentOTP(): String? = currentOTP

    fun getPrevOTP(): String? = prevOTP

    fun getPin(): String = pin

    /**
     * Sets the mOTP PIN. The bound is the one [validate] and the document parser apply, so a PIN
     * that would make the next vault write reject the entry is refused at the setter instead.
     */
    fun setPin(pin: String) {
        InputPolicy.text(pin, InputPolicy.MAX_TAG_CHARS)
        if (this.pin != pin) invalidateCachedToken()
        this.pin = pin
    }

    fun getListId(): Long = listId

    fun setListId(newId: Long) {
        listId = newId
    }

    /** Recalculates the token ({@code updateNow} forces a time-based refresh); returns whether one was produced. */
    fun updateOTP(updateNow: Boolean): Boolean = updateOTPAt(System.currentTimeMillis() / 1000, updateNow)

    /**
     * Time-based update with an explicit epoch second. Besides making all token variants use one
     * consistent clock sample, this refreshes when the wall clock moves backwards as well as
     * forwards; otherwise a cached token from a future step could remain visible until time caught
     * up. Internal visibility keeps this deterministic seam out of the Java API.
     */
    internal fun updateOTPAt(time: Long, updateNow: Boolean): Boolean {
        val seed = secret ?: return false
        validate()
        if (type == OTPType.TOTP || type == OTPType.STEAM || type == OTPType.MOTP) {
            val counter = time / getTokenStepSeconds()

            if (updateNow || !hasUpdatedTimeToken || counter != last_update) {
                // Reuse the cached token only when the clock advanced by exactly one step. A
                // backwards/large jump or edit recomputes both current and previous at this time.
                val reusePrevious = hasUpdatedTimeToken && !updateNow && last_update != Long.MAX_VALUE
                        && counter == last_update + 1
                prevOTP = if (reusePrevious && !currentOTP.isNullOrEmpty()) currentOTP else ""

                when (type) {
                    OTPType.TOTP -> {
                        currentOTP = Tools.formatTokenString(
                            TokenCalculator.TOTP_RFC6238(seed, period, time, digits, algorithm, 0), digits)

                        if (prevOTP.isNullOrEmpty())
                            prevOTP = Tools.formatTokenString(
                                TokenCalculator.TOTP_RFC6238(seed, period, time, digits, algorithm, -1), digits)
                    }
                    OTPType.STEAM -> {
                        currentOTP = TokenCalculator.TOTP_SteamAt(seed, period, time, digits, algorithm, 0)

                        if (prevOTP.isNullOrEmpty())
                            prevOTP = TokenCalculator.TOTP_SteamAt(seed, period, time, digits, algorithm, -1)
                    }
                    OTPType.MOTP -> {
                        val currentPin = getPin()

                        if (currentPin.isEmpty()) {
                            currentOTP = MOTP_NO_PIN_CODE
                            prevOTP = "" // the placeholder is not a token and must not be shown as one
                        } else {
                            currentOTP = TokenCalculator.MOTP(currentPin, String(seed), time, 0)

                            if (prevOTP.isNullOrEmpty())
                                prevOTP = TokenCalculator.MOTP(currentPin, String(seed), time, -1)
                        }
                    }
                    else -> Unit
                }

                last_update = counter
                hasUpdatedTimeToken = true
                setColor(COLOR_DEFAULT)
                return true
            }
            return false
        } else if (type == OTPType.HOTP) {
            currentOTP = TokenCalculator.HOTP(seed, counter, digits, algorithm)
            return true
        }
        return false
    }

    /**
     * Returns true the first time the token is within {@link #EXPIRY_TIME} seconds of expiring,
     * switching the entry to the expiring color. {@link #updateOTP(boolean)} resets that color.
     */
    fun hasColorChanged(): Boolean {
        if (color == COLOR_DEFAULT) {
            val time = System.currentTimeMillis() / 1000
            val step = getTokenStepSeconds()
            if ((time % step) > (step - EXPIRY_TIME)) {
                setColor(COLOR_RED)
                return true
            }
        }
        return false
    }

    /** Sets the thumbnail by exact, then by fuzzy issuer match. */
    private fun setThumbnailFromIssuer(issuer: String) {
        try {
            this.thumbnail = EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(issuer)
        } catch (error: Exception) {
            try {
                this.thumbnail = EntryThumbnail.EntryThumbnails.valueOfFuzzy(issuer)
            } catch (fallback: Exception) {
                this.thumbnail = EntryThumbnail.EntryThumbnails.Default
            }
        }
    }

    /**
     * The stored name of a document's thumbnail, or the default icon when the document names no
     * usable one. The document parser never guessed a thumbnail from the issuer.
     */
    private fun thumbnailFromName(name: String?): EntryThumbnail.EntryThumbnails {
        if (name == null) return EntryThumbnail.EntryThumbnails.Default
        return try {
            EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(name)
        } catch (unknown: Exception) {
            EntryThumbnail.EntryThumbnails.Default
        }
    }

    /**
     * The seed the serializers may dereference. The Java model passed its possibly-null field
     * straight into `String(byte[])`/`Base32`, so a destroyed entry failed with a
     * NullPointerException here; that behaviour is kept rather than silently writing an empty seed.
     */
    private fun ownedSecret(): ByteArray = secret ?: throw NullPointerException("Entry has no secret")

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true

        if (other == null || javaClass != other.javaClass)
            return false

        val entry = other as Entry

        return type == entry.type &&
                period == entry.period &&
                counter == entry.counter &&
                digits == entry.digits &&
                algorithm == entry.algorithm &&
                secret.contentEquals(entry.secret) &&
                label == entry.label &&
                issuer == entry.issuer
    }

    /**
     * Consistent with [equals], which compares the seed by content: equal entries must hash alike.
     * (The Java hash used the array identity of the seed, which broke that contract.)
     */
    override fun hashCode(): Int {
        return Objects.hash(type, period, counter, digits, algorithm, secret?.contentHashCode(), label, issuer)
    }

    fun setColor(color: Int) {
        this.color = color
    }

    fun getColor(): Int = color

    companion object {
        // Token placeholder shown while an mOTP entry has no PIN
        private const val MOTP_NO_PIN_CODE = "PINREQ"
        private const val COLOR_DEFAULT = 0
        private const val EXPIRY_TIME = 8               // Seconds before the period ends to highlight the token

        /** The color an entry switches to while its token is about to expire. */
        const val COLOR_RED = 1

        /** True if the secret is valid Base32 (or hex for mOTP). */
        @JvmStatic
        fun validateSecret(secret: String?, type: OTPType?): Boolean {
            try {
                if (type == OTPType.MOTP)
                    Hex.decodeHex(secret)
                else
                    decodeSecret(secret)
            } catch (error: Exception) {
                return false
            }

            return true
        }

        /** The manual-entry seed decoder: canonical Base32, or IllegalArgumentException. */
        private fun decodeSecret(encoded: String?): ByteArray =
            EntryJson.decodeBase32Secret(encoded) ?: throw IllegalArgumentException("Invalid secret")
    }
}
