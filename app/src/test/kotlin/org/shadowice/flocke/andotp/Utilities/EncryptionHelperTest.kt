/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for PBKDF2, AES-GCM, random generation, legacy key derivation and RSA paths.
 * The public IvParameterSpec overload is retained for compatibility, but normalizes to the
 * portable GCMParameterSpec contract internally so the same vectors run on Android and JCA.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

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
        assertEquals(32, first.encoded.size)  // PBKDF2_LENGTH / 8
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
    fun aesGcmUsesTheNistVector() {
        val key = EncryptionHelper.generateSymmetricKey(ByteArray(16))
        val nonce = ByteArray(12)
        val plaintext = ByteArray(16)
        val expected = "0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val encrypted = EncryptionHelper.encrypt(key, IvParameterSpec(nonce), plaintext)
        assertArrayEquals(expected, encrypted)
        assertArrayEquals(plaintext, EncryptionHelper.decrypt(key, nonce + encrypted))
    }

    @Test
    fun encryptedBackupFrameRoundTripsCurrentAndLegacyFormats() {
        val password = "backup password ! with punctuation"
        val plaintext = "[{\"secret\":\"JBSWY3DPEHPK3PXP\",\"label\":\"alice\"}]"
            .toByteArray(StandardCharsets.UTF_8)
        val iterations = 4096
        val backupSalt = ByteArray(Constants.ENCRYPTION_IV_LENGTH) { (it + 1).toByte() }
        val backupKey = EncryptionHelper.generateSymmetricKeyPBKDF2(password, iterations, backupSalt)
        val encrypted = EncryptionHelper.encrypt(backupKey, plaintext)
        val framed = ByteBuffer.allocate(Constants.INT_LENGTH + backupSalt.size + encrypted.size)
            .putInt(iterations).put(backupSalt).put(encrypted).array()

        val header = EncryptedBackupHeader.parse(framed)
        assertEquals(iterations, header.getIterations())
        assertArrayEquals(backupSalt, header.getSalt())
        assertArrayEquals(plaintext, EncryptionHelper.decrypt(
            EncryptionHelper.generateSymmetricKeyPBKDF2(password, header.getIterations(), header.getSalt()),
            header.getEncrypted()))

        // Historical encrypted backups stored nonce + GCM body without the current PBKDF2 header.
        val legacyKey = EncryptionHelper.generateSymmetricKeyFromPassword(password)
        val legacyFrame = EncryptionHelper.encrypt(legacyKey, plaintext)
        assertArrayEquals(plaintext, EncryptionHelper.decrypt(legacyKey, legacyFrame))

        val tampered = framed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        try {
            val alteredHeader = EncryptedBackupHeader.parse(tampered)
            EncryptionHelper.decrypt(
                EncryptionHelper.generateSymmetricKeyPBKDF2(password, alteredHeader.getIterations(), alteredHeader.getSalt()),
                alteredHeader.getEncrypted())
            throw AssertionError("tampered encrypted backup was accepted")
        } catch (expected: GeneralSecurityException) {
            // GCM tag verification rejects the altered ciphertext.
        }

        try {
            EncryptionHelper.decrypt(
                EncryptionHelper.generateSymmetricKeyPBKDF2("$password-wrong", iterations, backupSalt),
                header.getEncrypted())
            throw AssertionError("wrong backup password was accepted")
        } catch (expected: GeneralSecurityException) {
            // Authentication failure is distinct from a valid empty/imported document.
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
