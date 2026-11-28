package com.fairuniversal.offlineotp.domain


/** Android-free otpauth boundary. Expected malformed input returns Failure, not exceptions. */
object ProvisioningCodec {
    fun parse(input: String?): ProvisioningResult {
        if (input == null || input.isEmpty()) return ProvisioningResult.Failure(ProvisioningError.INVALID_URI)
        if (input.length > InterchangeLimits.MAX_URI_CHARS) return ProvisioningResult.Failure(ProvisioningError.INPUT_TOO_LONG)
        return try {
            parseBounded(input)
        } catch (invalid: Rejected) {
            ProvisioningResult.Failure(invalid.error)
        } catch (_: Exception) {
            ProvisioningResult.Failure(ProvisioningError.INVALID_URI)
        }
    }

    fun encode(kind: String, seed: ByteArray, period: Int, digits: Int, counter: Long,
               issuer: String, label: String, algorithm: String, tags: List<String>): String? = try {
        val value = PortableOtp.create(EntryRecordKind.valueOf(kind), seed, period, digits, counter,
            issuer, label, EntryRecordAlgorithm.valueOf(algorithm), tags)
        try {
            val secret = if (kind == "MOTP") seed.decodeToString(throwOnInvalidSequence = true) else InterchangeEncoding.base32(seed)
            val query = linkedMapOf("secret" to secret, "issuer" to issuer)
            if (kind == "HOTP") query["counter"] = counter.toString()
            if (kind != "MOTP" && period != 30) query["period"] = period.toString()
            if (digits != 6) query["digits"] = digits.toString()
            if (algorithm != "SHA1") query["algorithm"] = algorithm
            "otpauth://" + kind.lowercase() + "/" + percentEncode(label) + "?" +
                query.entries.joinToString("&") { it.key + "=" + percentEncode(it.value) } +
                tags.joinToString("") { "&tags=" + percentEncode(it) }
        } finally { value.destroy() }
    } catch (_: Exception) { null }

    private fun percentEncode(value: String): String {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        return try { buildString {
            for (raw in bytes) {
                val c = raw.toInt() and 255
                if (c in 65..90 || c in 97..122 || c in 48..57 || c.toChar() in "-_.~") append(c.toChar())
                else { append('%'); append("0123456789ABCDEF"[c ushr 4]); append("0123456789ABCDEF"[c and 15]) }
            }
        } } finally { bytes.fill(0) }
    }

    private fun parseBounded(input: String): ProvisioningResult.Success {
        // Encode raw spaces before java.net.URI parsing so unescaped spaces in labels still parse.
        val uri = RawInterchangeUri(input.replace(" ", "%20"))
        if (uri.scheme != "otpauth" || uri.isOpaque || uri.host == null ||
            uri.rawAuthority != uri.host || uri.rawFragment != null) reject(ProvisioningError.INVALID_URI)
        val kind = when (uri.host) {
            "totp" -> EntryRecordKind.TOTP
            "hotp" -> EntryRecordKind.HOTP
            "motp" -> EntryRecordKind.MOTP
            "steam" -> EntryRecordKind.STEAM
            else -> reject(ProvisioningError.UNSUPPORTED_TYPE)
        }
        val parameters = LinkedHashMap<String, String>()
        val tags = ArrayList<String>()
        uri.rawQuery?.split('&')?.forEach { part ->
            if (part.isEmpty()) reject(ProvisioningError.INVALID_URI)
            val separator = part.indexOf('=')
            val name = decode(if (separator < 0) part else part.substring(0, separator), true)
            val value = decode(if (separator < 0) "" else part.substring(separator + 1), true)
            if (name.isEmpty()) reject(ProvisioningError.INVALID_URI)
            if (name == "tags") {
                if (tags.size == InterchangeLimits.MAX_TAGS) reject(ProvisioningError.TOO_MANY_TAGS)
                boundedText(value, InterchangeLimits.MAX_TAG_CHARS)
                tags.add(value)
            } else {
                if (parameters.containsKey(name)) reject(ProvisioningError.DUPLICATE_PARAMETER)
                parameters[name] = value // Unknown singletons remain forward-compatible.
            }
        }
        val encodedSecret = parameters["secret"] ?: reject(ProvisioningError.MISSING_SECRET)
        if (encodedSecret.isEmpty()) reject(ProvisioningError.MISSING_SECRET)
        if (kind == EntryRecordKind.HOTP && !parameters.containsKey("counter")) reject(ProvisioningError.MISSING_COUNTER)
        // Validate supplied numbers even when the OTP variant does not use that field.
        val suppliedPeriod = parameters["period"]?.let(::integer) ?: 30L
        val digits = parameters["digits"]?.let(::integer) ?: if (kind == EntryRecordKind.STEAM) 5L else 6L
        val suppliedCounter = parameters["counter"]?.let(::integer) ?: 0L
        if (suppliedPeriod !in 1L..InterchangeLimits.MAX_PERIOD_SECONDS.toLong() || digits !in 1L..9L ||
            suppliedCounter < 0 || suppliedCounter == Long.MAX_VALUE ||
            (kind == EntryRecordKind.STEAM && digits != 5L)) reject(ProvisioningError.INVALID_PARAMETERS)
        val period = if (kind == EntryRecordKind.TOTP || kind == EntryRecordKind.STEAM) suppliedPeriod.toInt() else 30
        val counter = if (kind == EntryRecordKind.HOTP) suppliedCounter else 0L
        val algorithmName = parameters["algorithm"]?.uppercase() ?: "SHA1"
        val algorithm = EntryRecordAlgorithm.entries.firstOrNull { it.name == algorithmName }
            ?: reject(ProvisioningError.INVALID_ALGORITHM)
        val issuer = parameters["issuer"] ?: ""
        boundedText(issuer, InterchangeLimits.MAX_TEXT_CHARS)
        val path = decode(uri.rawPath ?: "", false)
        val fullLabel = path.removePrefix("/")
        val label = (if (issuer.isNotEmpty()) fullLabel.removePrefix("$issuer:") else fullLabel).trim { it <= ' ' }
        boundedText(label, InterchangeLimits.MAX_TEXT_CHARS)
        val secret = secret(encodedSecret, kind)
        try {
            return ProvisioningResult.Success(PortableOtp.create(kind, secret, period, digits.toInt(), counter,
                issuer, label, algorithm, tags))
        } finally { secret.fill(0) }
    }

    private fun integer(value: String): Long {
        if (!value.matches(Regex("-?[0-9]+"))) reject(ProvisioningError.INVALID_NUMBER)
        return value.toLongOrNull() ?: reject(ProvisioningError.INVALID_NUMBER)
    }

    private fun boundedText(value: String, limit: Int) {
        if (value.length > limit) reject(ProvisioningError.TEXT_TOO_LONG)
    }

    private fun secret(encoded: String, kind: EntryRecordKind): ByteArray {
        if (encoded.length > InterchangeLimits.MAX_SECRET_BYTES * 2) reject(ProvisioningError.INVALID_SECRET)
        if (kind == EntryRecordKind.MOTP) {
            if (encoded.length % 2 != 0 || !encoded.matches(Regex("[0-9a-fA-F]+"))) reject(ProvisioningError.INVALID_SECRET)
            val bytes = encoded.encodeToByteArray()
            if (bytes.isEmpty() || bytes.size > InterchangeLimits.MAX_SECRET_BYTES) {
                bytes.fill(0)
                reject(ProvisioningError.INVALID_SECRET)
            }
            return bytes
        }
        if (!encoded.matches(Regex("[A-Za-z2-7]+={0,6}"))) reject(ProvisioningError.INVALID_SECRET)
        val normalized = encoded.uppercase()
        val bytes = try { InterchangeEncoding.unbase32(normalized) } catch (_: Exception) { reject(ProvisioningError.INVALID_SECRET) }
        if (bytes.isEmpty() || bytes.size > InterchangeLimits.MAX_SECRET_BYTES) { bytes.fill(0); reject(ProvisioningError.INVALID_SECRET) }
        return bytes
    }

    private fun decode(value: String, plusAsSpace: Boolean): String =
        try { InterchangeEncoding.percent(value, plusAsSpace) } catch (_: Exception) { reject(ProvisioningError.INVALID_URI) }

    private class Rejected(val error: ProvisioningError) : IllegalArgumentException(error.name)
    private fun reject(error: ProvisioningError): Nothing = throw Rejected(error)
}
