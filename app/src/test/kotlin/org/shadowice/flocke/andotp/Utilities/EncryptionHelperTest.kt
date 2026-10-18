/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the Android-free crypto helpers: PBKDF2 splitting, random generation, the
 * legacy key derivation and the RSA paths.
 *
 * The AES-GCM encrypt/decrypt round trips are deliberately NOT run here: EncryptionHelper
 * initializes the GCM cipher with an IvParameterSpec, which Android's crypto providers accept
 * (the long-shipped andOTP behaviour this fork keeps) but a desktop SunJCE rejects with
 * "AlgorithmParameterSpec not of GCMParameterSpec". Those vectors run in the instrumentation
 * suite (ApplicationTest) on an Android runtime instead. Only the pre-provider framing checks
 * are JDK-safe and asserted here.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import javax.crypto.SecretKey

class EncryptionHelperTest {
    private val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)

    @Test
    fun pbkdf2CredentialsSplitTheOutput() {
        val credentials = EncryptionHelper.generatePBKDF2Credentials("correct horse battery staple", salt, 1000)
        assertEquals(16, credentials.key.size)
        assertEquals(16, credentials.password.size)
    }

    @Test
    fun pbkdf2IsDeterministicForEqualInputs() {
        val first = EncryptionHelper.generatePBKDF2Credentials("password", salt, 500)
        val second = EncryptionHelper.generatePBKDF2Credentials("password", salt, 500)
        assertArrayEquals(first.key, second.key)
        assertArrayEquals(first.password, second.password)

        val different = EncryptionHelper.generatePBKDF2Credentials("passworD", salt, 500)
        assertFalse(first.key.contentEquals(different.key))
    }

    @Test
    fun pbkdf2RejectsInvalidParameters() {
        for (call in listOf(
            { EncryptionHelper.generatePBKDF2Credentials(null, salt, 1000) },
            { EncryptionHelper.generatePBKDF2Credentials("password", null, 1000) },
            { EncryptionHelper.generatePBKDF2Credentials("password", ByteArray(7), 1000) },
            { EncryptionHelper.generatePBKDF2Credentials("password", salt, 0) },
            { EncryptionHelper.generatePBKDF2Credentials("password", salt, 20000001) },
            { EncryptionHelper.generatePBKDF2Credentials("x".repeat(InputPolicy.MAX_TEXT_CHARS + 1), salt, 1000) }
        )) {
            try {
                call()
                throw AssertionError("invalid PBKDF2 parameters accepted")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun symmetricKeyFromPasswordIsStable() {
        val first = EncryptionHelper.generateSymmetricKeyPBKDF2("password", 1000, salt)
        val second = EncryptionHelper.generateSymmetricKeyPBKDF2("password", 1000, salt)
        assertArrayEquals(first.encoded, second.encoded)
        assertEquals(32, first.encoded.size)  // PBKDF2_LENGTH / 2
    }

    @Test
    fun legacyKeyDerivationIsPlainSha256() {
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("password".toByteArray(Charsets.UTF_8))
        val key = EncryptionHelper.generateSymmetricKeyFromPassword("password")
        assertArrayEquals(expected, key.encoded)
    }

    @Test
    fun symmetricKeyWrapsRawBytes() {
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val key = EncryptionHelper.generateSymmetricKey(raw)
        assertEquals("AES", key.algorithm)
        assertArrayEquals(raw, key.encoded)
    }

    @Test
    fun randomBytesDifferBetweenCalls() {
        val first = EncryptionHelper.generateRandom(32)
        val second = EncryptionHelper.generateRandom(32)
        assertEquals(32, first.size)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun gcmFramingIsCheckedBeforeAnyProviderWork() {
        val key: SecretKey = EncryptionHelper.generateSymmetricKey(EncryptionHelper.generateRandom(32))
        // The decrypt entry point rejects null and every frame shorter than nonce + tag before
        // it touches a cipher; this part is provider-independent.
        for (truncated in listOf<ByteArray?>(null, ByteArray(0), ByteArray(27), ByteArray(11))) {
            try {
                EncryptionHelper.decrypt(key, truncated)
                throw AssertionError("truncated frame accepted: ${truncated?.size}")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun asymmetricEncryptionRoundTrips() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val plaintext = EncryptionHelper.generateRandom(31)
        val ciphertext = EncryptionHelper.encrypt(pair.public, plaintext)
        assertFalse(plaintext.contentEquals(ciphertext))
        assertArrayEquals(plaintext, EncryptionHelper.decrypt(pair.private, ciphertext))
    }

    @Test
    fun wrappedKeysRoundTripViaTheHelper() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val wrapper = SecretKeyWrapper(pair)
        val raw = EncryptionHelper.generateRandom(32)
        val key: SecretKey = EncryptionHelper.generateSymmetricKey(raw)
        val unwrapped = wrapper.unwrap(wrapper.wrap(key))
        assertArrayEquals(raw, unwrapped.encoded)
        assertTrue(unwrapped.encoded.size == 32)
    }
}
