package org.shadowice.flocke.andotp.Utilities

import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.TreeMap

/** Parsing failures never contain the scanned URI, account names or secrets. */
internal enum class MigrationError { INVALID_URI, TOO_LARGE, INVALID_DATA, UNSUPPORTED }

internal sealed class MigrationParseResult {
    class Success(val payload: MigrationPayload) : MigrationParseResult() {
        override fun toString(): String = "MigrationParseResult.Success(redacted)"
    }
    class Failure(val error: MigrationError) : MigrationParseResult()
}

/** Owns the validated accounts in one QR until transferred or closed. */
internal class MigrationPayload(
    val version: Int, val batchSize: Int, val batchIndex: Int, val batchId: Int,
    accounts: List<ValidatedOtp>
) : AutoCloseable {
    val count: Int = accounts.size
    private var owned: ArrayList<ValidatedOtp>? = ArrayList(accounts)

    @Synchronized
    fun takeAccounts(): ArrayList<ValidatedOtp> {
        val result = checkNotNull(owned) { "Migration payload already consumed" }
        owned = null
        return result
    }

    @Synchronized
    override fun close() {
        owned?.forEach { it.close() }
        owned = null
    }

    override fun toString(): String = "MigrationPayload(redacted)"
}

/**
 * Google Authenticator's export is a base64-encoded protobuf inside an otpauth-migration URI.
 * Its unofficial field layout is documented at https://github.com/qistoph/otp_export; the
 * seven-digit enum and optional unique ID also appear in krissrex/google-authenticator-exporter.
 * This reader is bounded, non-recursive and rejects unsupported token parameters rather than
 * silently importing accounts that would produce incorrect codes.
 */
internal object GoogleAuthenticatorMigration {
    private const val SCHEME = "otpauth-migration"
    private const val MAX_BATCHES = InputPolicy.MAX_ENTRIES

    fun isMigration(input: String?): Boolean = input?.startsWith("$SCHEME:", ignoreCase = true) == true

    fun parse(input: String?): MigrationParseResult {
        if (input.isNullOrEmpty()) return MigrationParseResult.Failure(MigrationError.INVALID_URI)
        if (input.length > InputPolicy.MAX_URI_CHARS) return MigrationParseResult.Failure(MigrationError.TOO_LARGE)
        var binary: ByteArray? = null
        return try {
            val uri = URI(input)
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || uri.isOpaque ||
                uri.rawAuthority != "offline" || (uri.rawPath != "" && uri.rawPath != "/") ||
                uri.rawFragment != null) reject(MigrationError.INVALID_URI)
            var data: String? = null
            val query = uri.rawQuery ?: reject(MigrationError.INVALID_URI)
            for (part in query.split('&')) {
                val separator = part.indexOf('=')
                if (separator <= 0) reject(MigrationError.INVALID_URI)
                if (part.substring(0, separator) == "data") {
                    if (data != null) reject(MigrationError.INVALID_URI)
                    data = part.substring(separator + 1)
                }
            }
            binary = decodeBase64(data ?: reject(MigrationError.INVALID_URI))
            MigrationParseResult.Success(readPayload(ProtoReader(binary, 0, binary.size)))
        } catch (invalid: Rejected) {
            MigrationParseResult.Failure(invalid.error)
        } catch (_: URISyntaxException) {
            MigrationParseResult.Failure(MigrationError.INVALID_URI)
        } catch (_: CharacterCodingException) {
            MigrationParseResult.Failure(MigrationError.INVALID_DATA)
        } catch (_: IllegalArgumentException) {
            MigrationParseResult.Failure(MigrationError.INVALID_DATA)
        } finally {
            binary?.fill(0)
        }
    }

    /** Keep literal '+' as base64, rather than treating it as a form-encoded space. */
    private fun decodeBase64(input: String): ByteArray {
        if (input.isEmpty()) reject(MigrationError.INVALID_DATA)
        val buffer = ByteArray(input.length)
        var normalized: ByteArray? = null
        try {
            var length = 0
            var i = 0
            while (i < input.length) {
                var c = input[i++].code
                if (c == '%'.code) {
                    if (i + 1 >= input.length) reject(MigrationError.INVALID_URI)
                    c = (hex(input[i++]) shl 4) or hex(input[i++])
                }
                c = when (c) {
                    '-'.code -> '+'.code
                    '_'.code -> '/'.code
                    else -> c
                }
                if (c !in 'A'.code..'Z'.code && c !in 'a'.code..'z'.code &&
                    c !in '0'.code..'9'.code && c != '+'.code && c != '/'.code && c != '='.code)
                    reject(MigrationError.INVALID_DATA)
                buffer[length++] = c.toByte()
            }
            normalized = buffer.copyOf(length)
            return Base64.getDecoder().decode(normalized)
        } finally {
            normalized?.fill(0)
            buffer.fill(0)
        }
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> reject(MigrationError.INVALID_URI)
    }

    private fun readPayload(reader: ProtoReader): MigrationPayload {
        val accounts = ArrayList<ValidatedOtp>()
        var version = 0
        var batchSize = 0
        var batchIndex = 0
        var batchId = 0
        val seen = BooleanArray(6)
        try {
            while (!reader.done()) {
                val (field, wire) = reader.tag()
                if (field in 2..5) {
                    if (seen[field]) reject(MigrationError.INVALID_DATA)
                    seen[field] = true
                }
                when (field) {
                    1 -> {
                        reader.expectWire(wire, 2)
                        if (accounts.size >= InputPolicy.MAX_ENTRIES) reject(MigrationError.TOO_LARGE)
                        accounts.add(readAccount(reader.message()))
                    }
                    2 -> { reader.expectWire(wire, 0); version = reader.nonNegativeInt() }
                    3 -> { reader.expectWire(wire, 0); batchSize = reader.nonNegativeInt() }
                    4 -> { reader.expectWire(wire, 0); batchIndex = reader.nonNegativeInt() }
                    5 -> { reader.expectWire(wire, 0); batchId = reader.signedInt32() }
                    else -> reject(MigrationError.UNSUPPORTED)
                }
            }
            if (version !in 0..2) reject(MigrationError.UNSUPPORTED)
            if (batchSize == 0) batchSize = 1 // Proto3 omits default fields.
            if (batchSize > MAX_BATCHES || batchIndex >= batchSize || accounts.isEmpty())
                reject(MigrationError.INVALID_DATA)
            return MigrationPayload(version, batchSize, batchIndex, batchId, accounts)
        } catch (error: Exception) {
            accounts.forEach { it.close() }
            throw error
        }
    }

    private fun readAccount(reader: ProtoReader): ValidatedOtp {
        var secret: ByteArray? = null
        var name = ""
        var issuer = ""
        var algorithm = 0
        var digits = 0
        var type = 0
        var counter = 0L
        val seen = BooleanArray(9)
        try {
            while (!reader.done()) {
                val (field, wire) = reader.tag()
                if (field in 1..8) {
                    if (seen[field]) reject(MigrationError.INVALID_DATA)
                    seen[field] = true
                }
                when (field) {
                    1 -> { reader.expectWire(wire, 2); secret = reader.bytes(InputPolicy.MAX_SECRET_BYTES) }
                    2 -> { reader.expectWire(wire, 2); name = reader.text() }
                    3 -> { reader.expectWire(wire, 2); issuer = reader.text() }
                    4 -> { reader.expectWire(wire, 0); algorithm = reader.nonNegativeInt() }
                    5 -> { reader.expectWire(wire, 0); digits = reader.nonNegativeInt() }
                    6 -> { reader.expectWire(wire, 0); type = reader.nonNegativeInt() }
                    7 -> { reader.expectWire(wire, 0); counter = reader.varint() }
                    // Google may include an account identifier; it does not affect OTP generation.
                    8 -> { reader.expectWire(wire, 2); reader.text() }
                    else -> reject(MigrationError.UNSUPPORTED)
                }
            }
            val seed = secret ?: reject(MigrationError.INVALID_DATA)
            if (seed.isEmpty() || counter < 0 || counter == Long.MAX_VALUE) reject(MigrationError.INVALID_DATA)
            val hash = when (algorithm) {
                0, 1 -> OtpHashAlgorithm.SHA1
                2 -> OtpHashAlgorithm.SHA256
                3 -> OtpHashAlgorithm.SHA512
                else -> reject(MigrationError.UNSUPPORTED)
            }
            val digitCount = when (digits) {
                0, 1 -> 6
                2 -> 8
                3 -> 7
                else -> reject(MigrationError.UNSUPPORTED)
            }
            val kind = when (type) {
                0, 2 -> OtpKind.TOTP
                1 -> OtpKind.HOTP
                else -> reject(MigrationError.UNSUPPORTED)
            }
            val colon = name.indexOf(':')
            if (issuer.isEmpty() && colon > 0) {
                issuer = name.substring(0, colon).trim()
                name = name.substring(colon + 1).trim()
            } else if (issuer.isNotEmpty() && name.startsWith("$issuer:")) {
                name = name.substring(issuer.length + 1).trim()
            }
            return ValidatedOtp.create(kind, seed, 30, digitCount,
                if (kind == OtpKind.HOTP) counter else 0, issuer, name, hash, emptyList())
        } finally {
            secret?.fill(0)
        }
    }

    /** A fixed-depth protobuf wire reader with strict length and varint bounds. */
    private class ProtoReader(private val data: ByteArray, private var position: Int, private val end: Int) {
        fun done(): Boolean = position == end

        fun tag(): Pair<Int, Int> {
            val tag = varint()
            if (tag <= 0 || tag > 0xffffffffL) reject(MigrationError.INVALID_DATA)
            val field = (tag ushr 3).toInt()
            if (field == 0 || field > 0x1fffffff) reject(MigrationError.INVALID_DATA)
            return field to (tag.toInt() and 7)
        }

        fun expectWire(actual: Int, expected: Int) {
            if (actual != expected) reject(MigrationError.INVALID_DATA)
        }

        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                if (position >= end) reject(MigrationError.INVALID_DATA)
                val byte = data[position++].toInt() and 0xff
                if (shift == 63 && byte > 1) reject(MigrationError.INVALID_DATA)
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return value
            }
            reject(MigrationError.INVALID_DATA)
        }

        fun nonNegativeInt(): Int {
            val value = varint()
            if (value < 0 || value > Int.MAX_VALUE) reject(MigrationError.INVALID_DATA)
            return value.toInt()
        }

        fun signedInt32(): Int {
            val value = varint()
            if (value >= 0 && value <= 0xffffffffL) return value.toInt()
            if (value < 0 && value.toInt().toLong() == value) return value.toInt()
            reject(MigrationError.INVALID_DATA)
        }

        private fun range(max: Int): IntRange {
            val length = nonNegativeInt()
            if (length > max || length > end - position) reject(MigrationError.INVALID_DATA)
            val start = position
            position += length
            return start until position
        }

        fun message(): ProtoReader {
            val part = range(InputPolicy.MAX_URI_CHARS)
            return ProtoReader(data, part.first, part.last + 1)
        }

        fun bytes(max: Int): ByteArray {
            val part = range(max)
            return data.copyOfRange(part.first, part.last + 1)
        }

        fun text(): String {
            val part = range(InputPolicy.MAX_TEXT_CHARS * 4)
            val value = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, part.first, part.last + 1 - part.first)).toString()
            if (value.length > InputPolicy.MAX_TEXT_CHARS) reject(MigrationError.TOO_LARGE)
            return value
        }

    }

    private class Rejected(val error: MigrationError) : IllegalArgumentException()
    private fun reject(error: MigrationError): Nothing = throw Rejected(error)
}

internal enum class MigrationBatchStatus { ADDED, COMPLETE, DUPLICATE, DIFFERENT_EXPORT, TOO_MANY, STALE }

/** Holds a single, session-bound export in memory until every QR has been scanned. */
internal class MigrationBatchCollector(private val generation: Long) : AutoCloseable {
    private val parts = TreeMap<Int, MigrationPayload>()
    private var version = -1
    private var batchId = 0
    private var batchSize = 0
    private var total = 0
    private var closed = false

    val received: Int @Synchronized get() = parts.size
    val expected: Int @Synchronized get() = batchSize

    /** Always consumes [payload], including on rejection. */
    @Synchronized
    fun add(payload: MigrationPayload, currentGeneration: Long, sessionReady: Boolean): MigrationBatchStatus {
        if (closed || !sessionReady || currentGeneration != generation) {
            payload.close()
            close()
            return MigrationBatchStatus.STALE
        }
        if (parts.isEmpty()) {
            version = payload.version
            batchId = payload.batchId
            batchSize = payload.batchSize
        } else if (version != payload.version || batchId != payload.batchId || batchSize != payload.batchSize) {
            payload.close()
            return MigrationBatchStatus.DIFFERENT_EXPORT
        }
        if (parts.containsKey(payload.batchIndex)) {
            payload.close()
            return MigrationBatchStatus.DUPLICATE
        }
        if (payload.count > InputPolicy.MAX_ENTRIES - total) {
            payload.close()
            close()
            return MigrationBatchStatus.TOO_MANY
        }
        parts[payload.batchIndex] = payload
        total += payload.count
        return if (parts.size == batchSize) MigrationBatchStatus.COMPLETE else MigrationBatchStatus.ADDED
    }

    /** Transfers all accounts in QR order exactly once after the whole batch is present. */
    @Synchronized
    fun takeAccounts(currentGeneration: Long, sessionReady: Boolean): ArrayList<ValidatedOtp>? {
        if (closed || !sessionReady || currentGeneration != generation || parts.size != batchSize || parts.isEmpty()) {
            close()
            return null
        }
        val result = ArrayList<ValidatedOtp>(total)
        try {
            for (payload in parts.values) result.addAll(payload.takeAccounts())
            parts.clear()
            closed = true
            return result
        } catch (error: RuntimeException) {
            result.forEach { it.close() }
            close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        parts.values.forEach { it.close() }
        parts.clear()
        closed = true
    }

    override fun toString(): String = "MigrationBatchCollector(redacted)"
}
