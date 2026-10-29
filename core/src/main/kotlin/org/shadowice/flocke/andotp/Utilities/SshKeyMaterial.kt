package org.shadowice.flocke.andotp.Utilities

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Bounded, platform-independent storage for a pair of verbatim SSH key texts. */
object SshKeyMaterial {
    const val MAX_KEY_BYTES = 32 * 1024
    const val MAX_ENCODED_BYTES = 2 * MAX_KEY_BYTES + 8

    /** Not a data class: the generated toString/equals must never expose private key material. */
    class Keys(val publicKey: String, val privateKey: String) {
        override fun toString(): String = "SshKeyMaterial.Keys(redacted)"
    }

    /** Each field is optional, but at least one must contain non-whitespace text. */
    @JvmStatic
    fun encode(publicKey: String, privateKey: String): ByteArray {
        require(publicKey.isNotBlank() || privateKey.isNotBlank()) { "Enter a public or private key" }
        val publicBytes = encodeText(publicKey)
        try {
            val privateBytes = encodeText(privateKey)
            try {
                return ByteBuffer.allocate(8 + publicBytes.size + privateBytes.size)
                    .putInt(publicBytes.size).put(publicBytes)
                    .putInt(privateBytes.size).put(privateBytes).array()
            } finally {
                privateBytes.fill(0)
            }
        } finally {
            publicBytes.fill(0)
        }
    }

    /** Fail closed on invalid framing, oversized fields and malformed UTF-8. */
    @JvmStatic
    fun decode(bytes: ByteArray): Keys {
        require(bytes.size in 8..MAX_ENCODED_BYTES) { "Invalid SSH key entry" }
        val input = ByteBuffer.wrap(bytes)
        fun field(): String {
            require(input.remaining() >= 4) { "Invalid SSH key entry" }
            val size = input.int
            require(size in 0..MAX_KEY_BYTES && size <= input.remaining()) { "Invalid SSH key entry" }
            val value = ByteArray(size)
            input.get(value)
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString()
            } catch (error: CharacterCodingException) {
                throw IllegalArgumentException("Invalid SSH key text", error)
            } finally {
                value.fill(0)
            }
        }
        val publicKey = field()
        val privateKey = field()
        require(!input.hasRemaining() && (publicKey.isNotBlank() || privateKey.isNotBlank())) {
            "Invalid SSH key entry"
        }
        return Keys(publicKey, privateKey)
    }

    private fun encodeText(value: String): ByteArray {
        // Avoid allocating an oversized byte buffer for hostile input before encoding.
        require(value.length <= MAX_KEY_BYTES) { "SSH key exceeds 32 KiB" }
        val encoded = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Invalid SSH key text", error)
        }
        try {
            require(encoded.remaining() <= MAX_KEY_BYTES) { "SSH key exceeds 32 KiB" }
            return ByteArray(encoded.remaining()).also { encoded.get(it) }
        } finally {
            if (encoded.hasArray()) encoded.array().fill(0)
            else for (index in 0 until encoded.limit()) encoded.put(index, 0)
        }
    }
}
