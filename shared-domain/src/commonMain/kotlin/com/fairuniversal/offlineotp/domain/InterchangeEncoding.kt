package com.fairuniversal.offlineotp.domain

/** Bounded text encodings used by provisioning/interchange, never crypto primitives. */
internal object InterchangeEncoding {
    fun percent(value: String, plusAsSpace: Boolean): String {
        val raw = value.encodeToByteArray(throwOnInvalidSequence = true)
        val out = ByteArray(raw.size)
        try {
            var i = 0; var count = 0
            while (i < raw.size) {
                val next = raw[i++].toInt() and 255
                out[count++] = when {
                    next == 37 -> {
                        require(i + 1 < raw.size)
                        val high = (raw[i++].toInt() and 255).toChar().digitToIntOrNull(16) ?: throw IllegalArgumentException()
                        val low = (raw[i++].toInt() and 255).toChar().digitToIntOrNull(16) ?: throw IllegalArgumentException()
                        ((high shl 4) or low).toByte()
                    }
                    next == 43 && plusAsSpace -> 32
                    else -> next.toByte()
                }
            }
            return out.decodeToString(0, count, throwOnInvalidSequence = true)
        } finally { raw.fill(0); out.fill(0) }
    }
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    fun base32(bytes: ByteArray): String = buildString {
        var bits = 0; var buffer = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 255); bits += 8
            while (bits >= 5) { bits -= 5; append(ALPHABET[(buffer ushr bits) and 31]) }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
        while (length % 8 != 0) append('=')
    }
    fun unbase32(text: String): ByteArray {
        require(text.matches(Regex("[A-Za-z2-7]+={0,6}")))
        val normalized = text.uppercase(); val body = normalized.trimEnd('=')
        val out = ByteArray(body.length * 5 / 8)
        var bits = 0; var buffer = 0; var offset = 0
        for (c in body) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(c); bits += 5
            if (bits >= 8) { bits -= 8; if (offset < out.size) out[offset++] = (buffer ushr bits).toByte() }
        }
        val canonical = base32(out)
        if (normalized != canonical && normalized != canonical.trimEnd('=')) { out.fill(0); throw IllegalArgumentException() }
        return out
    }
    fun unbase64(text: String): ByteArray {
        val body = text.trimEnd('=')
        val padding = text.length - body.length
        require(body.length % 4 != 1 && (padding == 0 || (text.length % 4 == 0 && padding == (4 - body.length % 4) % 4)))
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = ByteArray(body.length * 6 / 8)
        var bits = 0; var buffer = 0; var offset = 0
        try {
            for (c in body) {
                val value = alphabet.indexOf(c); require(value >= 0)
                buffer = (buffer shl 6) or value; bits += 6
                if (bits >= 8) { bits -= 8; out[offset++] = (buffer ushr bits).toByte() }
            }
            return out
        } catch (error: IllegalArgumentException) { out.fill(0); throw error }
    }
}

/** Strict hierarchical URI splitting; percent decoding remains explicit and exactly once. */
internal class RawInterchangeUri(input: String) {
    val scheme: String
    val rawAuthority: String
    val rawPath: String
    val rawQuery: String?
    val rawFragment: String?
    val host: String?
    val isOpaque = false
    init {
        require(input.none { it <= ' ' || it in "<>\"{}|\\^`" })
        input.encodeToByteArray(throwOnInvalidSequence = true).fill(0)
        var i = 0
        while (i < input.length) {
            if (input[i++] == '%') { require(i + 1 < input.length && input[i].digitToIntOrNull(16) != null && input[i + 1].digitToIntOrNull(16) != null); i += 2 }
        }
        val colon = input.indexOf("://"); require(colon > 0)
        scheme = input.substring(0, colon)
        require(scheme.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*")))
        val start = colon + 3
        val end = input.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) input.length else it }
        rawAuthority = input.substring(start, end)
        host = rawAuthority.takeIf { it.isNotEmpty() && it.none { c -> c in "@:[]%" } }
        val hash = input.indexOf('#', end).let { if (it < 0) input.length else it }
        rawFragment = if (hash < input.length) input.substring(hash + 1) else null
        val question = input.indexOf('?', end).takeIf { it in end until hash }
        rawPath = input.substring(end, question ?: hash)
        rawQuery = question?.let { input.substring(it + 1, hash) }
        require(rawPath.none { it == '[' || it == ']' })
    }
}
