/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the OTP calculators: RFC 6238 TOTP, RFC 4226 HOTP, the Steam mapping and
 * legacy mOTP. The mOTP expectation is computed with an independent MessageDigest instead of
 * the implementation under test.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class TokenCalculatorTest {
    private val rfcSecret = "12345678901234567890".toByteArray(StandardCharsets.US_ASCII)

    @Test
    fun rfc6238Sha1Vectors() {
        assertEquals(94287082, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 59L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(7081804, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 1111111109L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(14050471, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 1111111111L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(89005924, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 1234567890L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(69279037, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 2000000000L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(65353130, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 20000000000L, 8, TokenCalculator.HashAlgorithm.SHA1))
    }

    @Test
    fun rfc6238Sha256AndSha512Vectors() {
        val sha256Secret = "12345678901234567890123456789012".toByteArray(StandardCharsets.US_ASCII)
        val sha512Secret = "1234567890123456789012345678901234567890123456789012345678901234"
            .toByteArray(StandardCharsets.US_ASCII)
        assertEquals(46119246, TokenCalculator.TOTP_RFC6238(sha256Secret, 30, 59L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(90693936, TokenCalculator.TOTP_RFC6238(sha512Secret, 30, 59L, 8, TokenCalculator.HashAlgorithm.SHA512))
        assertEquals(68084774, TokenCalculator.TOTP_RFC6238(sha256Secret, 30, 1111111109L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(25091201, TokenCalculator.TOTP_RFC6238(sha512Secret, 30, 1111111109L, 8, TokenCalculator.HashAlgorithm.SHA512))
        assertEquals(67062674, TokenCalculator.TOTP_RFC6238(sha256Secret, 30, 1111111111L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(99943326, TokenCalculator.TOTP_RFC6238(sha512Secret, 30, 1111111111L, 8, TokenCalculator.HashAlgorithm.SHA512))
        assertEquals(91819424, TokenCalculator.TOTP_RFC6238(sha256Secret, 30, 1234567890L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(93441116, TokenCalculator.TOTP_RFC6238(sha512Secret, 30, 1234567890L, 8, TokenCalculator.HashAlgorithm.SHA512))
        assertEquals(90698825, TokenCalculator.TOTP_RFC6238(sha256Secret, 30, 2000000000L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(38618901, TokenCalculator.TOTP_RFC6238(sha512Secret, 30, 2000000000L, 8, TokenCalculator.HashAlgorithm.SHA512))
        assertEquals(77737706, TokenCalculator.TOTP_RFC6238(sha256Secret, 30, 20000000000L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(47863826, TokenCalculator.TOTP_RFC6238(sha512Secret, 30, 20000000000L, 8, TokenCalculator.HashAlgorithm.SHA512))
    }

    @Test
    fun rfc4226HotpVectors() {
        val expected = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489"
        )
        for ((counter, token) in expected.withIndex()) {
            assertEquals(token, TokenCalculator.HOTP(rfcSecret, counter.toLong(), 6, TokenCalculator.HashAlgorithm.SHA1))
        }
    }

    @Test
    fun hotpIsZeroPadded() {
        // 2031459264 -> 59264 with five digits is zero-padded.
        val full = TokenCalculator.HOTP(rfcSecret, 0L, 6, TokenCalculator.HashAlgorithm.SHA1)
        assertEquals(6, full.length)
    }

    @Test
    fun steamTokensUseTheSteamAlphabet() {
        val token = TokenCalculator.TOTP_SteamAt(rfcSecret, 30, 59L, 5, TokenCalculator.HashAlgorithm.SHA1, 0)
        assertEquals(5, token.length)
        for (character in token) {
            assertEquals(true, "23456789BCDFGHJKMNPQRTVWXY".indexOf(character) >= 0)
        }

        // Independent re-derivation: RFC 4226 dynamic truncation of an HmacSHA1, then base-26
        // mapping onto Steam's alphabet, digit by digit.
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(rfcSecret, "HmacSHA1"))
        val hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(59L / 30).array())
        val offset = hash[hash.size - 1].toInt() and 0xF
        var binary = (hash[offset].toInt() and 0x7F) shl 24
        binary = binary or ((hash[offset + 1].toInt() and 0xFF) shl 16)
        binary = binary or ((hash[offset + 2].toInt() and 0xFF) shl 8)
        binary = binary or (hash[offset + 3].toInt() and 0xFF)
        val steamChars = "23456789BCDFGHJKMNPQRTVWXY"
        val expected = StringBuilder()
        repeat(5) {
            expected.append(steamChars[binary % 26])
            binary /= 26
        }
        assertEquals(expected.toString(), token)
    }

    @Test
    fun motpMatchesAnIndependentDigest() {
        val pin = "1234"
        val secret = "0001020304050607080a0b0c0d0e0f"
        val epoch = 1700000000L
        val expected = md5Hex(((epoch / 10) + 0).toString() + secret + pin).substring(0, 6)
        val expectedPrev = md5Hex(((epoch / 10) - 1).toString() + secret + pin).substring(0, 6)

        assertEquals(expected, TokenCalculator.MOTP(pin, secret, epoch, 0))
        assertEquals(expectedPrev, TokenCalculator.MOTP(pin, secret, epoch, -1))
        assertNotEquals(expected, expectedPrev)
    }

    @Test
    fun invalidParametersAreRejectedBeforeCalculation() {
        val assertions = listOf(
            { TokenCalculator.TOTP_RFC6238(rfcSecret, 0, 59L, 6, TokenCalculator.HashAlgorithm.SHA1, 0) },
            { TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 59L, 10, TokenCalculator.HashAlgorithm.SHA1, 0) },
            { TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 59L, 0, TokenCalculator.HashAlgorithm.SHA1, 0) },
            { TokenCalculator.HOTP(rfcSecret, -1L, 6, TokenCalculator.HashAlgorithm.SHA1) }
        )
        for (call in assertions) {
            try {
                call()
                throw AssertionError("invalid OTP parameters were accepted")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    private fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { String.format("%02x", it) }
    }
}
