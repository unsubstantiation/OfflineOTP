// Sandbox-only stand-in for the commons-codec Base32 the entry model and the OTP parser
// round-trip secrets through.
//
// Replication, not an inert surface: the entry document boundary is executed here, and its seed
// check compares the decoded seed's canonical encoding with the stored text. The encoder emits the
// RFC 4648 uppercase, padded form commons-codec's Base32 produces, and the decoder inverts it.
// Decoder-strictness differences are invisible to that check: text that does not round-trip
// through this pair is rejected either way, exactly as an invalid or canonical-mismatched seed was
// before. The real library's exception types and its leniency for over-padded input are not
// replicated. See ../../../../../../README.md.

package org.apache.commons.codec.binary

import java.io.ByteArrayOutputStream

class Base32 {
    fun decode(data: String?): ByteArray =
        decode(data?.toByteArray(Charsets.ISO_8859_1))

    fun decode(data: ByteArray?): ByteArray {
        if (data == null) return ByteArray(0)
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            val character = byte.toInt() and 0xFF
            if (character == '='.code) continue
            val value = ALPHABET.indexOf(character.toChar())
            if (value < 0) throw IllegalArgumentException("Invalid Base32 character")
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.write((buffer shr (bitsLeft - 8)) and 0xFF)
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }

    fun encode(data: ByteArray?): ByteArray {
        if (data == null) return ByteArray(0)
        val out = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) out.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        while (out.length % 8 != 0) out.append('=')
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    fun encodeAsString(data: ByteArray?): String = String(encode(data))

    private companion object {
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}
