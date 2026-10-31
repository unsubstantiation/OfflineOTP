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

import org.apache.commons.codec.binary.Base32
import org.apache.commons.codec.binary.Hex
import org.json.JSONException
import org.json.JSONObject
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.PasswordMaterial
import org.shadowice.flocke.andotp.Utilities.BrowserPasswordCsv
import org.shadowice.flocke.andotp.Utilities.OtpHashAlgorithm
import org.shadowice.flocke.andotp.Utilities.OtpKind
import org.shadowice.flocke.andotp.Utilities.SshConnection
import org.shadowice.flocke.andotp.Utilities.SshKeyMaterial
import org.shadowice.flocke.andotp.Utilities.EmailMaterial
import org.shadowice.flocke.andotp.Utilities.OtpParseResult
import org.shadowice.flocke.andotp.Utilities.OtpUriParser
import org.shadowice.flocke.andotp.Utilities.ThumbnailCatalog
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.Utilities.TokenFormat
import org.shadowice.flocke.andotp.Utilities.ValidatedOtp

import java.nio.charset.StandardCharsets
import java.util.Objects

/** One vault item (OTP or other secret type), serialized to the encrypted JSON vault format. */
open class Entry {
    /** Supported vault item types. */
    enum class OTPType {
        TOTP, HOTP, MOTP, STEAM, NOTE, SSH_KEY, PASSWORD, PASSWORD_ARCHIVE, EMAIL
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
    // Separate from Android's temporary tap-to-reveal state; persisted with the entry.
    private var detailsVisible = false
    private var hideTask: Runnable? = null
    private var last_update = 0L
    private var hasUpdatedTimeToken = false
    private var last_used = 0L
    private var used_frequency = 0L

    /** Tags assigned to this entry. */
    @JvmField
    var tags: MutableList<String> = ArrayList()

    private var thumbnailName: String = ThumbnailCatalog.DEFAULT_NAME
    private var color = COLOR_DEFAULT
    private var pin = ""
    // Optional connection metadata; only SSH key entries may carry it.
    private var sshConnection: SshConnection.Details? = null
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
        if (type == OTPType.NOTE) InputPolicy.note(secret)
        this.secret = secret.toByteArray(StandardCharsets.UTF_8)
        this.issuer = issuer ?: ""
        this.label = label ?: ""
        this.tags = tags
        this.period = TokenCalculator.TOTP_DEFAULT_PERIOD
        setThumbnailFromIssuer(this.issuer)
        validate()
    }

    /** Parses an `otpauth://` URI via [OtpUriParser], throwing on invalid input. */
    @Throws(Exception::class)
    constructor(contents: String?) {
        val result = OtpUriParser.parse(contents)
        if (result is OtpParseResult.Failure) {
            throw IllegalArgumentException("Invalid OTP URI: " + result.error.name)
        }
        val value = (result as OtpParseResult.Success).value
        try {
            initialize(value)
        } finally {
            value.close()
        }
    }

    /** Copies a validated account; the caller retains ownership of [value]. */
    constructor(value: ValidatedOtp) {
        initialize(value)
    }

    private fun initialize(value: ValidatedOtp) {
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
        }
    }

    /** Parses a stored JSON entry via [EntryJson], throwing on invalid input. */
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
            this.thumbnailName = ThumbnailCatalog.normalizeName(value.thumbnailName)
            this.last_used = value.lastUsed
            this.used_frequency = value.usedFrequency
            this.detailsVisible = value.detailsVisible
            this.pin = value.pin
            this.sshConnection = value.sshConnection
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
    @Suppress("SENSELESS_COMPARISON")
    fun validate() {
        val seed = secret
        val maxSecret = when (type) {
            OTPType.SSH_KEY -> SshKeyMaterial.MAX_ENCODED_BYTES
            OTPType.PASSWORD, OTPType.PASSWORD_ARCHIVE -> PasswordMaterial.MAX_BYTES
            OTPType.EMAIL -> EmailMaterial.MAX_BYTES
            else -> InputPolicy.MAX_SECRET_BYTES
        }
        if (seed == null || seed.isEmpty() || seed.size > maxSecret)
            throw IllegalArgumentException("Invalid entry")
        InputPolicy.tokenParameters(period, digits, counter)
        if (type == OTPType.NOTE) {
            InputPolicy.noteFromBytes(seed)
            require(label.isNotBlank()) { "A note needs a title" }
        }
        if (type == OTPType.SSH_KEY) {
            SshKeyMaterial.decode(seed)
            require(label.isNotBlank()) { "An SSH key needs a title" }
        } else {
            // Connection metadata is SSH-only; a stale value must never ride along on another kind.
            require(sshConnection == null) { "Only SSH key entries carry connection fields" }
        }
        if (type == OTPType.PASSWORD) PasswordMaterial.decode(seed)
        if (type == OTPType.PASSWORD || type == OTPType.PASSWORD_ARCHIVE) require(label.isNotBlank())
        if (type == OTPType.EMAIL) EmailMaterial.decode(seed)
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
        sshConnection = null
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
        thumbnailName = getThumbnailName(),
        lastUsed = getLastUsed(),
        usedFrequency = getUsedFrequency(),
        tags = tags,
        pin = getPin(),
        detailsVisible = detailsVisible,
        sshConnection = sshConnection
    )

    /**
     * Serializes this entry to an otpauth:// URI string. Pure JVM percent-encoding (spaces become
     * `%20`, never `+`) keeps this Android-free and byte-compatible with the [OtpUriParser] rules.
     */
    fun toUriString(): String {
        require(isTimeBased() || isCounterBased()) { "This entry cannot be exported as an OTP URI" }
        val typeName = when (type) {
            OTPType.TOTP -> "totp"
            OTPType.HOTP -> "hotp"
            OTPType.STEAM -> "steam"
            OTPType.MOTP -> "motp"
            OTPType.NOTE, OTPType.SSH_KEY, OTPType.PASSWORD, OTPType.PASSWORD_ARCHIVE, OTPType.EMAIL -> throw IllegalArgumentException("This entry cannot be exported as an OTP URI")
        }

        val query = LinkedHashMap<String, String>()
        if (type == OTPType.MOTP)
            query["secret"] = String(ownedSecret())
        else
            query["secret"] = Base32().encodeAsString(ownedSecret())

        query["issuer"] = issuer

        if (type == OTPType.HOTP) {
            query["counter"] = counter.toString()
        }
        // Export non-default TOTP/Steam periods. HOTP also emits a legacy period, but import ignores it.
        if (type == OTPType.HOTP || type == OTPType.TOTP || type == OTPType.STEAM) {
            if (period != TokenCalculator.TOTP_DEFAULT_PERIOD)
                query["period"] = period.toString()
        }
        if (digits != TokenCalculator.TOTP_DEFAULT_DIGITS) {
            query["digits"] = digits.toString()
        }
        if (algorithm != TokenCalculator.DEFAULT_ALGORITHM) {
            query["algorithm"] = algorithm.name
        }

        val builder = StringBuilder("otpauth://")
            .append(typeName)
            .append('/')
            .append(percentEncode(label))
            .append('?')
            .append(query.entries.joinToString("&") { "${it.key}=${percentEncode(it.value)}" })
        for (tag in tags) {
            builder.append("&tags=").append(percentEncode(tag))
        }
        return builder.toString()
    }

    /** RFC 3986 unreserved characters pass through; every other byte (including space and `+`) is encoded. */
    private fun percentEncode(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val builder = StringBuilder(bytes.size)
        for (raw in bytes) {
            val c = raw.toInt() and 0xFF
            val unreserved = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) ||
                    (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code ||
                    c == '.'.code || c == '~'.code
            if (unreserved) builder.append(c.toChar()) else builder.append('%').append(String.format("%02X", c))
        }
        return builder.toString()
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
        if (this.type != type) {
            invalidateCachedToken()
            setColor(COLOR_DEFAULT)
            // Connection metadata belongs to the key entry; dropping the type must drop it too.
            if (type != OTPType.SSH_KEY) sshConnection = null
        }
        this.type = type
    }

    fun isNote(): Boolean = type == OTPType.NOTE
    fun isSshKey(): Boolean = type == OTPType.SSH_KEY
    fun isPassword(): Boolean = type == OTPType.PASSWORD
    fun isPasswordArchive(): Boolean = type == OTPType.PASSWORD_ARCHIVE
    fun isEmail(): Boolean = type == OTPType.EMAIL

    fun getEmailAccount(): EmailMaterial.Account {
        require(isEmail()) { "Not an email entry" }
        return EmailMaterial.decode(ownedSecret())
    }

    fun getPasswordFields(): PasswordMaterial.Fields {
        require(isPassword())
        return PasswordMaterial.decode(ownedSecret())
    }

    /** The exact imported bytes, only for explicit plaintext export. Caller must wipe them. */
    fun exportPasswordArchive(): ByteArray {
        require(isPasswordArchive())
        return ownedSecret().copyOf()
    }

    /** Keys are only exposed on an explicit edit or copy action; never via OTP calculation. */
    fun getSshKeys(): SshKeyMaterial.Keys {
        require(isSshKey()) { "Not an SSH key entry" }
        return SshKeyMaterial.decode(ownedSecret())
    }

    fun setSshKeys(publicKey: String, privateKey: String) {
        require(isSshKey()) { "Not an SSH key entry" }
        val encoded = SshKeyMaterial.encode(publicKey, privateKey)
        try { setSecret(encoded) } finally { encoded.fill(0) }
    }

    /**
     * Optional host/port/user/password for this key, or null when the entry stores none. Values are
     * built and validated by [SshConnection.of], so an entry never holds a partial or out-of-range
     * connection.
     */
    fun getSshConnection(): SshConnection.Details? = sshConnection

    /** Replaces the connection metadata; null removes it. SSH key entries only. */
    fun setSshConnection(details: SshConnection.Details?) {
        if (details != null) require(isSshKey()) { "Not an SSH key entry" }
        sshConnection = details
    }

    /** True when at least one of host/port/user/password is stored. */
    fun hasSshConnection(): Boolean = sshConnection?.isSet() ?: false

    /** Explicit access only: notes are never calculated or displayed as OTPs. */
    fun getNote(): String {
        require(isNote()) { "Not a note" }
        return InputPolicy.noteFromBytes(ownedSecret())
    }

    fun setNote(value: String) {
        require(isNote()) { "Not a note" }
        InputPolicy.note(value)
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try { setSecret(bytes) } finally { bytes.fill(0) }
    }

    fun getSecret(): ByteArray? = secret?.clone()

    /** Size only, for enforcing import budgets without copying secret bytes. */
    fun getSecretSize(): Int = secret?.size ?: 0

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
     * Seconds per token step. mOTP uses a 10-second step regardless of its stored period;
     * imports default that period to 30 seconds, but entries can be changed later.
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
        // Copy: callers keep handing over their own working lists (the edit dialog passes the
        // adapter's active tags), and a later mutation there must not silently edit the entry.
        this.tags = ArrayList(tags)
    }

    /** The persisted thumbnail name; the UI layer resolves it to a drawable (unknown names render as default). */
    fun getThumbnailName(): String = thumbnailName

    fun setThumbnailName(value: String) {
        thumbnailName = ThumbnailCatalog.normalizeName(value)
    }

    /**
     * Applies an edit and validates the entry. On a [RuntimeException], restores issuer, label,
     * digits, period, counter, tags, thumbnail, the SSH connection metadata and the secret before
     * rethrowing. Other fields are not rolled back, so callers must not mutate them if they rely on
     * this rollback.
     */
    fun applyEdit(mutate: (Entry) -> Unit) {
        val previousIssuer = issuer
        val previousLabel = label
        val previousDigits = digits
        val previousPeriod = period
        val previousCounter = counter
        val previousTags = ArrayList(tags)
        val previousThumbnail = thumbnailName
        val previousSshConnection = sshConnection
        val previousSecret = secret?.clone()
        try {
            mutate(this)
            validate()
        } catch (error: RuntimeException) {
            // Restore the numeric parameters first: setIssuer() validates, so it must not run
            // while a rejected period/digits/counter is still in place.
            setPeriod(previousPeriod)
            setDigits(previousDigits)
            setCounter(previousCounter)
            setTags(previousTags)
            setLabel(previousLabel)
            setSecret(previousSecret)
            setIssuer(previousIssuer, false)
            thumbnailName = previousThumbnail
            sshConnection = previousSshConnection
            throw error
        } finally {
            previousSecret?.fill(0)
        }
    }

    fun getAlgorithm(): TokenCalculator.HashAlgorithm = algorithm

    /** Legacy stored-period check; use [hasNonDefaultTokenStep] for countdown display. */
    fun hasNonDefaultPeriod(): Boolean = period != TokenCalculator.TOTP_DEFAULT_PERIOD

    /** Whether the actual rollover differs from the global 30-second TOTP countdown. */
    fun hasNonDefaultTokenStep(): Boolean =
        isTimeBased() && getTokenStepSeconds() != TokenCalculator.TOTP_DEFAULT_PERIOD

    /** Persistent privacy setting, independent of the temporary Android tap-to-reveal setting. */
    fun areDetailsVisible(): Boolean = detailsVisible
    fun setDetailsVisible(value: Boolean) { detailsVisible = value }

    /** A single short identity, preferring the label and falling back to the issuer. */
    fun privacyTitle(): String = label.ifBlank { issuer }

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

    /**
     * True when a real token is cached and safe to put on the clipboard. The mOTP no-PIN
     * placeholder is explicitly excluded: it is a UI prompt, not a one-time password.
     */
    fun hasCopyableToken(): Boolean =
        !currentOTP.isNullOrEmpty() && currentOTP != MOTP_NO_PIN_CODE

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

    /** Recalculates the token ([updateNow] forces a time-based refresh); returns whether it was recalculated. */
    fun updateOTP(updateNow: Boolean): Boolean = updateOTPAt(System.currentTimeMillis() / 1000, updateNow)

    /**
     * Updates the token using an explicit epoch-second timestamp [time], refreshing on both forward
     * and backward step changes so a future-step token never stays cached after a clock rollback.
     */
    fun updateOTPAt(time: Long, updateNow: Boolean): Boolean {
        val seed = secret ?: return false
        if (!isTimeBased() && !isCounterBased()) return false
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
                        currentOTP = TokenFormat.formatTokenString(
                            TokenCalculator.TOTP_RFC6238(seed, period, time, digits, algorithm, 0), digits)

                        if (prevOTP.isNullOrEmpty())
                            prevOTP = TokenFormat.formatTokenString(
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

    /** Updates the expiry color; HOTP and the mOTP PIN placeholder do not expire. */
    fun hasColorChanged(): Boolean = hasColorChangedAt(System.currentTimeMillis() / 1000)

    /** Explicit time lets the expiry boundary be checked without waiting for the system clock. */
    internal fun hasColorChangedAt(time: Long): Boolean {
        val next = if (isTimeBased() && (type != OTPType.MOTP || pin.isNotEmpty())) {
            val step = getTokenStepSeconds().toLong()
            val remaining = step - Math.floorMod(time, step)
            if (remaining <= EXPIRY_TIME) COLOR_RED else COLOR_DEFAULT
        } else COLOR_DEFAULT
        if (next == color) return false
        setColor(next)
        return true
    }

    /** Sets the thumbnail name by exact, then fuzzy whole-word issuer match, then the default. */
    private fun setThumbnailFromIssuer(issuer: String) {
        this.thumbnailName = ThumbnailCatalog.resolveName(issuer)
    }

    /** Returns the secret bytes, or throws [NullPointerException] if the entry has been destroyed. */
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
                issuer == entry.issuer &&
                sshIdentity() == entry.sshIdentity()
    }

    /**
     * The connection target participates in [equals]/[hashCode] so one key can back several hosts
     * or login names without collapsing into one entry. The password is excluded: it is a secret
     * that changes independently of the target.
     */
    private fun sshIdentity(): List<String>? = sshConnection?.identityKey()

    /** Hashes entry fields using content-based secret hashing to match [equals]. */
    override fun hashCode(): Int {
        return Objects.hash(type, period, counter, digits, algorithm, secret?.contentHashCode(), label,
            issuer, sshIdentity())
    }

    fun setColor(color: Int) {
        this.color = color
    }

    fun getColor(): Int = color

    companion object {
        /** Imports a browser row while retaining all original columns inside the encrypted vault. */
        @JvmStatic
        fun password(columns: List<String>, row: BrowserPasswordCsv.Row): Entry {
            val material = PasswordMaterial.encode(columns, row)
            val value = Entry()
            try {
                val fields = PasswordMaterial.decode(material)
                val issuer = fields.value("name").ifBlank { fields.value("title") }.ifBlank { fields.value("url") }
                val username = fields.value("username")
                value.type = OTPType.PASSWORD
                value.secret = material.copyOf()
                value.issuer = issuer
                value.label = username.ifBlank { "Password" }
                value.setThumbnailFromIssuer(issuer)
                value.validate()
                return value
            } catch (error: RuntimeException) {
                value.destroy()
                throw error
            } finally { material.fill(0) }
        }

        /** The payload is the original file, byte for byte; it is encrypted with the vault. */
        @JvmStatic
        fun passwordArchive(filename: String, data: ByteArray): Entry {
            require(data.isNotEmpty() && data.size <= PasswordMaterial.MAX_BYTES)
            val value = Entry()
            try {
                value.type = OTPType.PASSWORD_ARCHIVE
                value.secret = data.copyOf()
                value.label = filename
                value.validate()
                return value
            } catch (error: RuntimeException) { value.destroy(); throw error }
        }

        /** Creates one encrypted Email entry, carrying the complete FairEmail account and identity. */
        @JvmStatic
        fun email(account: org.json.JSONObject, identity: org.json.JSONObject,
                  identities: List<org.json.JSONObject> = listOf(identity), tags: MutableList<String> = ArrayList()): Entry {
            val material = EmailMaterial.encode(account, identities)
            val value = Entry()
            try {
                value.type = OTPType.EMAIL
                value.secret = material.copyOf()
                value.issuer = identity.optString("email").orEmpty()
                value.label = account.optString("name").orEmpty().ifBlank { value.issuer }
                value.tags = ArrayList(tags)
                value.setThumbnailFromIssuer(value.issuer)
                value.validate()
                return value
            } catch (error: RuntimeException) {
                value.destroy()
                throw error
            } finally { material.fill(0) }
        }

        /**
         * Creates one vault entry for a public and/or private SSH key, optionally with the
         * connection metadata that goes with it.
         */
        @JvmStatic
        @JvmOverloads
        fun sshKey(publicKey: String, privateKey: String, issuer: String, label: String,
                   tags: MutableList<String>,
                   connection: SshConnection.Details? = null): Entry {
            val value = Entry()
            try {
                value.type = OTPType.SSH_KEY
                value.secret = SshKeyMaterial.encode(publicKey, privateKey)
                value.issuer = issuer
                value.label = label
                value.tags = ArrayList(tags)
                value.sshConnection = connection
                value.setThumbnailFromIssuer(issuer)
                value.validate()
                return value
            } catch (error: RuntimeException) {
                value.destroy()
                throw error
            }
        }

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
