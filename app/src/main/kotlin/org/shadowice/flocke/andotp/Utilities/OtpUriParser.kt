package org.shadowice.flocke.andotp.Utilities

import org.apache.commons.codec.binary.Base32
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale

/** Android-free otpauth boundary. Expected malformed input returns Failure, not exceptions. */
object OtpUriParser {
    @JvmStatic
    fun parse(input: String?): OtpParseResult {
        if (input == null || input.isEmpty()) return OtpParseResult.Failure(OtpParseError.INVALID_URI)
        if (input.length > InputPolicy.MAX_URI_CHARS) return OtpParseResult.Failure(OtpParseError.INPUT_TOO_LONG)
        return try {
            parseBounded(input)
        } catch (invalid: Rejected) {
            OtpParseResult.Failure(invalid.error)
        } catch (_: URISyntaxException) {
            OtpParseResult.Failure(OtpParseError.INVALID_URI)
        } catch (_: CharacterCodingException) {
            OtpParseResult.Failure(OtpParseError.INVALID_URI)
        }
    }

    private fun parseBounded(input: String): OtpParseResult.Success {
        // Encode raw spaces before java.net.URI parsing so unescaped spaces in labels still parse.
        val uri = URI(input.replace(" ", "%20"))
        if (uri.scheme != "otpauth" || uri.isOpaque || uri.host == null ||
            uri.rawAuthority != uri.host || uri.rawFragment != null) reject(OtpParseError.INVALID_URI)
        val kind = when (uri.host) {
            "totp" -> OtpKind.TOTP
            "hotp" -> OtpKind.HOTP
            "motp" -> OtpKind.MOTP
            "steam" -> OtpKind.STEAM
            else -> reject(OtpParseError.UNSUPPORTED_TYPE)
        }
        val parameters = LinkedHashMap<String, String>()
        val tags = ArrayList<String>()
        uri.rawQuery?.split('&')?.forEach { part ->
            if (part.isEmpty()) reject(OtpParseError.INVALID_URI)
            val separator = part.indexOf('=')
            val name = decode(if (separator < 0) part else part.substring(0, separator), true)
            val value = decode(if (separator < 0) "" else part.substring(separator + 1), true)
            if (name.isEmpty()) reject(OtpParseError.INVALID_URI)
            if (name == "tags") {
                if (tags.size == InputPolicy.MAX_TAGS) reject(OtpParseError.TOO_MANY_TAGS)
                boundedText(value, InputPolicy.MAX_TAG_CHARS)
                tags.add(value)
            } else {
                if (parameters.containsKey(name)) reject(OtpParseError.DUPLICATE_PARAMETER)
                parameters[name] = value // Unknown singletons remain forward-compatible.
            }
        }
        val encodedSecret = parameters["secret"] ?: reject(OtpParseError.MISSING_SECRET)
        if (encodedSecret.isEmpty()) reject(OtpParseError.MISSING_SECRET)
        if (kind == OtpKind.HOTP && !parameters.containsKey("counter")) reject(OtpParseError.MISSING_COUNTER)
        // Validate supplied numbers even when the OTP variant does not use that field.
        val suppliedPeriod = parameters["period"]?.let(::integer) ?: 30L
        val digits = parameters["digits"]?.let(::integer) ?: if (kind == OtpKind.STEAM) 5L else 6L
        val suppliedCounter = parameters["counter"]?.let(::integer) ?: 0L
        if (suppliedPeriod !in 1L..InputPolicy.MAX_PERIOD_SECONDS.toLong() || digits !in 1L..9L ||
            suppliedCounter < 0 || suppliedCounter == Long.MAX_VALUE ||
            (kind == OtpKind.STEAM && digits != 5L)) reject(OtpParseError.INVALID_PARAMETERS)
        val period = if (kind == OtpKind.TOTP || kind == OtpKind.STEAM) suppliedPeriod.toInt() else 30
        val counter = if (kind == OtpKind.HOTP) suppliedCounter else 0L
        val algorithmName = parameters["algorithm"]?.uppercase(Locale.ROOT) ?: "SHA1"
        val algorithm = OtpHashAlgorithm.entries.firstOrNull { it.name == algorithmName }
            ?: reject(OtpParseError.INVALID_ALGORITHM)
        val issuer = parameters["issuer"] ?: ""
        boundedText(issuer, InputPolicy.MAX_TEXT_CHARS)
        val path = decode(uri.rawPath ?: "", false)
        val fullLabel = path.removePrefix("/")
        val label = (if (issuer.isNotEmpty()) fullLabel.removePrefix("$issuer:") else fullLabel).trim { it <= ' ' }
        boundedText(label, InputPolicy.MAX_TEXT_CHARS)
        val secret = secret(encodedSecret, kind)
        try {
            return OtpParseResult.Success(ValidatedOtp.create(kind, secret, period, digits.toInt(), counter,
                issuer, label, algorithm, tags))
        } finally { secret.fill(0) }
    }

    private fun integer(value: String): Long {
        if (!value.matches(Regex("-?[0-9]+"))) reject(OtpParseError.INVALID_NUMBER)
        return value.toLongOrNull() ?: reject(OtpParseError.INVALID_NUMBER)
    }

    private fun boundedText(value: String, limit: Int) {
        if (value.length > limit) reject(OtpParseError.TEXT_TOO_LONG)
    }

    private fun secret(encoded: String, kind: OtpKind): ByteArray {
        if (encoded.length > InputPolicy.MAX_SECRET_BYTES * 2) reject(OtpParseError.INVALID_SECRET)
        if (kind == OtpKind.MOTP) {
            if (encoded.length % 2 != 0 || !encoded.matches(Regex("[0-9a-fA-F]+"))) reject(OtpParseError.INVALID_SECRET)
            val bytes = encoded.toByteArray(UTF_8)
            if (bytes.isEmpty() || bytes.size > InputPolicy.MAX_SECRET_BYTES) {
                bytes.fill(0)
                reject(OtpParseError.INVALID_SECRET)
            }
            return bytes
        }
        if (!encoded.matches(Regex("[A-Za-z2-7]+={0,6}"))) reject(OtpParseError.INVALID_SECRET)
        val normalized = encoded.uppercase(Locale.ROOT)
        val codec = Base32()
        val bytes = codec.decode(normalized)
        val canonical = codec.encodeAsString(bytes)
        if (bytes.isEmpty() || bytes.size > InputPolicy.MAX_SECRET_BYTES ||
            (normalized != canonical && normalized != canonical.trimEnd('='))) {
            bytes.fill(0)
            reject(OtpParseError.INVALID_SECRET)
        }
        return bytes
    }

    /** Decode exactly once, rejecting malformed escapes/UTF-8 instead of replacement text. */
    private fun decode(value: String, plusAsSpace: Boolean): String {
        val raw = UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        val output = ByteArray(raw.remaining())
        var count = 0
        try {
            while (raw.hasRemaining()) {
                val next = raw.get().toInt() and 0xff
                output[count++] = when {
                    next == '%'.code -> {
                        if (raw.remaining() < 2) reject(OtpParseError.INVALID_URI)
                        val high = hex(raw.get().toInt() and 0xff)
                        val low = hex(raw.get().toInt() and 0xff)
                        ((high shl 4) or low).toByte()
                    }
                    next == '+'.code && plusAsSpace -> ' '.code.toByte()
                    else -> next.toByte()
                }
            }
            return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output, 0, count)).toString()
        } finally {
            output.fill(0)
            if (raw.hasArray()) raw.array().fill(0)
        }
    }

    private fun hex(value: Int): Int = when (value) {
        in '0'.code..'9'.code -> value - '0'.code
        in 'a'.code..'f'.code -> value - 'a'.code + 10
        in 'A'.code..'F'.code -> value - 'A'.code + 10
        else -> reject(OtpParseError.INVALID_URI)
    }

    private class Rejected(val error: OtpParseError) : IllegalArgumentException(error.name)
    private fun reject(error: OtpParseError): Nothing = throw Rejected(error)
}
