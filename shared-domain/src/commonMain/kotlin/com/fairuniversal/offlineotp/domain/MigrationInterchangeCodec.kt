package com.fairuniversal.offlineotp.domain

enum class PortableMigrationError { INVALID_URI, TOO_LARGE, INVALID_DATA, UNSUPPORTED }
sealed class PortableMigrationResult {
    class Success(val payload: PortableMigrationPayload) : PortableMigrationResult() { override fun toString() = "PortableMigrationResult.Success(redacted)" }
    class Failure(val error: PortableMigrationError) : PortableMigrationResult()
}
class PortableMigrationPayload(val version: Int, val batchSize: Int, val batchIndex: Int, val batchId: Int, accounts: List<PortableOtp>) {
    private var owned: List<PortableOtp>? = accounts.toList()
    fun takeAccounts(): List<PortableOtp> { val result = checkNotNull(owned); owned = null; return result }
    fun destroy() { owned?.forEach { it.destroy() }; owned = null }
    override fun toString() = "PortableMigrationPayload(redacted)"
}

/**
 * Google Authenticator's export is a base64-encoded protobuf inside an otpauth-migration URI.
 * Its unofficial field layout is documented at https://github.com/qistoph/otp_export; the
 * seven-digit enum and optional unique ID also appear in krissrex/google-authenticator-exporter.
 * This reader is bounded, non-recursive and rejects unsupported token parameters rather than
 * silently importing accounts that would produce incorrect codes.
 */
object MigrationInterchangeCodec {
    private const val SCHEME = "otpauth-migration"
    private const val MAX_BATCHES = InterchangeLimits.MAX_ENTRIES

    fun isMigration(input: String?): Boolean = input?.startsWith("$SCHEME:", ignoreCase = true) == true

    fun parse(input: String?): PortableMigrationResult {
        if (input.isNullOrEmpty()) return PortableMigrationResult.Failure(PortableMigrationError.INVALID_URI)
        if (input.length > InterchangeLimits.MAX_URI_CHARS) return PortableMigrationResult.Failure(PortableMigrationError.TOO_LARGE)
        var binary: ByteArray? = null
        return try {
            val uri = try { RawInterchangeUri(input) } catch (_: Exception) { reject(PortableMigrationError.INVALID_URI) }
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || uri.isOpaque ||
                uri.rawAuthority != "offline" || (uri.rawPath != "" && uri.rawPath != "/") ||
                uri.rawFragment != null) reject(PortableMigrationError.INVALID_URI)
            var data: String? = null
            val query = uri.rawQuery ?: reject(PortableMigrationError.INVALID_URI)
            for (part in query.split('&')) {
                val separator = part.indexOf('=')
                if (separator <= 0) reject(PortableMigrationError.INVALID_URI)
                if (part.substring(0, separator) == "data") {
                    if (data != null) reject(PortableMigrationError.INVALID_URI)
                    data = part.substring(separator + 1)
                }
            }
            binary = decodeBase64(data ?: reject(PortableMigrationError.INVALID_URI))
            PortableMigrationResult.Success(readPayload(ProtoReader(binary, 0, binary.size)))
        } catch (invalid: Rejected) {
            PortableMigrationResult.Failure(invalid.error)
        } catch (_: Exception) {
            PortableMigrationResult.Failure(PortableMigrationError.INVALID_DATA)
        } finally {
            binary?.fill(0)
        }
    }

    /** Keep literal '+' as base64, rather than treating it as a form-encoded space. */
    private fun decodeBase64(input: String): ByteArray {
        if (input.isEmpty()) reject(PortableMigrationError.INVALID_DATA)
        val buffer = ByteArray(input.length)
        var normalized: ByteArray? = null
        try {
            var length = 0
            var i = 0
            while (i < input.length) {
                var c = input[i++].code
                if (c == '%'.code) {
                    if (i + 1 >= input.length) reject(PortableMigrationError.INVALID_URI)
                    c = (hex(input[i++]) shl 4) or hex(input[i++])
                }
                c = when (c) {
                    '-'.code -> '+'.code
                    '_'.code -> '/'.code
                    else -> c
                }
                if (c !in 'A'.code..'Z'.code && c !in 'a'.code..'z'.code &&
                    c !in '0'.code..'9'.code && c != '+'.code && c != '/'.code && c != '='.code)
                    reject(PortableMigrationError.INVALID_DATA)
                buffer[length++] = c.toByte()
            }
            normalized = buffer.copyOf(length)
            return InterchangeEncoding.unbase64(normalized.decodeToString())
        } finally {
            normalized?.fill(0)
            buffer.fill(0)
        }
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> reject(PortableMigrationError.INVALID_URI)
    }

    private fun readPayload(reader: ProtoReader): PortableMigrationPayload {
        val accounts = ArrayList<PortableOtp>()
        var version = 0
        var batchSize = 0
        var batchIndex = 0
        var batchId = 0
        val seen = BooleanArray(6)
        try {
            while (!reader.done()) {
                val (field, wire) = reader.tag()
                if (field in 2..5) {
                    if (seen[field]) reject(PortableMigrationError.INVALID_DATA)
                    seen[field] = true
                }
                when (field) {
                    1 -> {
                        reader.expectWire(wire, 2)
                        if (accounts.size >= InterchangeLimits.MAX_ENTRIES) reject(PortableMigrationError.TOO_LARGE)
                        accounts.add(readAccount(reader.message()))
                    }
                    2 -> { reader.expectWire(wire, 0); version = reader.nonNegativeInt() }
                    3 -> { reader.expectWire(wire, 0); batchSize = reader.nonNegativeInt() }
                    4 -> { reader.expectWire(wire, 0); batchIndex = reader.nonNegativeInt() }
                    5 -> { reader.expectWire(wire, 0); batchId = reader.signedInt32() }
                    else -> reject(PortableMigrationError.UNSUPPORTED)
                }
            }
            if (version !in 0..2) reject(PortableMigrationError.UNSUPPORTED)
            if (batchSize == 0) batchSize = 1 // Proto3 omits default fields.
            if (batchSize > MAX_BATCHES || batchIndex >= batchSize || accounts.isEmpty())
                reject(PortableMigrationError.INVALID_DATA)
            return PortableMigrationPayload(version, batchSize, batchIndex, batchId, accounts)
        } catch (error: Exception) {
            accounts.forEach { it.destroy() }
            throw error
        }
    }

    private fun readAccount(reader: ProtoReader): PortableOtp {
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
                    if (seen[field]) reject(PortableMigrationError.INVALID_DATA)
                    seen[field] = true
                }
                when (field) {
                    1 -> { reader.expectWire(wire, 2); secret = reader.bytes(InterchangeLimits.MAX_SECRET_BYTES) }
                    2 -> { reader.expectWire(wire, 2); name = reader.text() }
                    3 -> { reader.expectWire(wire, 2); issuer = reader.text() }
                    4 -> { reader.expectWire(wire, 0); algorithm = reader.nonNegativeInt() }
                    5 -> { reader.expectWire(wire, 0); digits = reader.nonNegativeInt() }
                    6 -> { reader.expectWire(wire, 0); type = reader.nonNegativeInt() }
                    7 -> { reader.expectWire(wire, 0); counter = reader.varint() }
                    // Google may include an account identifier; it does not affect OTP generation.
                    8 -> { reader.expectWire(wire, 2); reader.text() }
                    else -> reject(PortableMigrationError.UNSUPPORTED)
                }
            }
            val seed = secret ?: reject(PortableMigrationError.INVALID_DATA)
            if (seed.isEmpty() || counter < 0 || counter == Long.MAX_VALUE) reject(PortableMigrationError.INVALID_DATA)
            val hash = when (algorithm) {
                0, 1 -> EntryRecordAlgorithm.SHA1
                2 -> EntryRecordAlgorithm.SHA256
                3 -> EntryRecordAlgorithm.SHA512
                else -> reject(PortableMigrationError.UNSUPPORTED)
            }
            val digitCount = when (digits) {
                0, 1 -> 6
                2 -> 8
                3 -> 7
                else -> reject(PortableMigrationError.UNSUPPORTED)
            }
            val kind = when (type) {
                0, 2 -> EntryRecordKind.TOTP
                1 -> EntryRecordKind.HOTP
                else -> reject(PortableMigrationError.UNSUPPORTED)
            }
            val colon = name.indexOf(':')
            if (issuer.isEmpty() && colon > 0) {
                issuer = name.substring(0, colon).trim()
                name = name.substring(colon + 1).trim()
            } else if (issuer.isNotEmpty() && name.startsWith("$issuer:")) {
                name = name.substring(issuer.length + 1).trim()
            }
            return PortableOtp.create(kind, seed, 30, digitCount,
                if (kind == EntryRecordKind.HOTP) counter else 0, issuer, name, hash, emptyList())
        } finally {
            secret?.fill(0)
        }
    }

    /** A fixed-depth protobuf wire reader with strict length and varint bounds. */
    private class ProtoReader(private val data: ByteArray, private var position: Int, private val end: Int) {
        fun done(): Boolean = position == end

        fun tag(): Pair<Int, Int> {
            val tag = varint()
            if (tag <= 0 || tag > 0xffffffffL) reject(PortableMigrationError.INVALID_DATA)
            val field = (tag ushr 3).toInt()
            if (field == 0 || field > 0x1fffffff) reject(PortableMigrationError.INVALID_DATA)
            return field to (tag.toInt() and 7)
        }

        fun expectWire(actual: Int, expected: Int) {
            if (actual != expected) reject(PortableMigrationError.INVALID_DATA)
        }

        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                if (position >= end) reject(PortableMigrationError.INVALID_DATA)
                val byte = data[position++].toInt() and 0xff
                if (shift == 63 && byte > 1) reject(PortableMigrationError.INVALID_DATA)
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return value
            }
            reject(PortableMigrationError.INVALID_DATA)
        }

        fun nonNegativeInt(): Int {
            val value = varint()
            if (value < 0 || value > Int.MAX_VALUE) reject(PortableMigrationError.INVALID_DATA)
            return value.toInt()
        }

        fun signedInt32(): Int {
            val value = varint()
            if (value >= 0 && value <= 0xffffffffL) return value.toInt()
            if (value < 0 && value.toInt().toLong() == value) return value.toInt()
            reject(PortableMigrationError.INVALID_DATA)
        }

        private fun range(max: Int): IntRange {
            val length = nonNegativeInt()
            if (length > max || length > end - position) reject(PortableMigrationError.INVALID_DATA)
            val start = position
            position += length
            return start until position
        }

        fun message(): ProtoReader {
            val part = range(InterchangeLimits.MAX_URI_CHARS)
            return ProtoReader(data, part.first, part.last + 1)
        }

        fun bytes(max: Int): ByteArray {
            val part = range(max)
            return data.copyOfRange(part.first, part.last + 1)
        }

        fun text(): String {
            val part = range(InterchangeLimits.MAX_TEXT_CHARS * 4)
            val value = data.decodeToString(part.first, part.last + 1, throwOnInvalidSequence = true)
            if (value.length > InterchangeLimits.MAX_TEXT_CHARS) reject(PortableMigrationError.TOO_LARGE)
            return value
        }

    }

    private class Rejected(val error: PortableMigrationError) : IllegalArgumentException()
    private fun reject(error: PortableMigrationError): Nothing = throw Rejected(error)
}
