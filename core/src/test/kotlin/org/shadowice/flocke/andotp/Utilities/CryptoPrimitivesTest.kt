/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the PBKDF2 credential factory that the desktop password vault shares with the
 * Android AuthenticationTask. The RFC 6070 vector and the split ordering pin byte-identical
 * credentials across platforms.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

class CryptoPrimitivesTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format("%02x", it) }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    @Test
    fun pbkdf2CredentialsMatchRfc6070AndTheIndependentVector() {
        // RFC 6070, PBKDF2-HMAC-SHA1, c = 4096, 200-bit vector; the factory derives 256 bits, so
        // the published vector covers its 25-byte prefix.
        val password = "passwordPASSWORDpassword"
        val salt = ascii("saltSALTsaltSALTsaltSALTsaltSALTsalt")
        val derived = CryptoPrimitives.generatePBKDF2Credentials(password, salt, 4096)

        val combined = derived.key + derived.password
        assertEquals("3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038",
            hex(combined.copyOfRange(0, 25)))

        // The lower half is the AES key, the upper half is the stored verifier.
        assertEquals(16, derived.key.size)
        assertEquals(16, derived.password.size)
        assertEquals(hex(combined.copyOfRange(0, 16)), hex(derived.key))
        assertEquals(hex(combined.copyOfRange(16, 32)), hex(derived.password))
    }

    @Test
    fun everyDerivationReturnsFreshWipeableArrays() {
        val salt = ByteArray(16) { it.toByte() }
        val first = CryptoPrimitives.generatePBKDF2Credentials("password", salt, 1000)
        val second = CryptoPrimitives.generatePBKDF2Credentials("password", salt, 1000)

        assertTrue(first.key !== second.key)
        assertTrue(first.password !== second.password)
        assertTrue(first.key.contentEquals(second.key))

        Arrays.fill(first.key, 0)
        Arrays.fill(first.password, 0)
        // Wiping one result must never corrupt another derivation.
        assertTrue(second.key.any { it != 0.toByte() })
    }

    @Test
    fun invalidCredentialParametersAreRejectedBeforeTheProvider() {
        val salt = ByteArray(8) { 1 }
        val tooLong = "x".repeat(InputPolicy.MAX_TEXT_CHARS + 1)
        for (invalid in listOf<() -> Unit>(
            { CryptoPrimitives.generatePBKDF2Credentials(null, salt, 1000) },
            { CryptoPrimitives.generatePBKDF2Credentials("password", null, 1000) },
            { CryptoPrimitives.generatePBKDF2Credentials("password", ByteArray(7), 1000) },
            { CryptoPrimitives.generatePBKDF2Credentials("password", salt, 0) },
            { CryptoPrimitives.generatePBKDF2Credentials("password", salt, 20_000_001) },
            { CryptoPrimitives.generatePBKDF2Credentials(tooLong, salt, 1000) }
        )) {
            try {
                invalid()
                throw AssertionError("invalid credential parameters accepted")
            } catch (expected: IllegalArgumentException) {
                // required
            }
        }

        // The boundaries themselves remain accepted.
        val boundary = CryptoPrimitives.generatePBKDF2Credentials(
            "y".repeat(InputPolicy.MAX_TEXT_CHARS), salt, 1)
        assertEquals(16, boundary.key.size)
    }
}
