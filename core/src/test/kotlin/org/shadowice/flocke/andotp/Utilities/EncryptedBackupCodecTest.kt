/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the shared password-encrypted backup codec. These pin the on-disk framing
 * (iterations + salt + nonce-prefixed AES-GCM) and the current/legacy decryption paths that make
 * encrypted backups interchangeable between the Android app and the desktop client.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException

class EncryptedBackupCodecTest {

    private val password = "backup password ! with punctuation"
    private val plaintext =
        "[{\"secret\":\"JBSWY3DPEHPK3PXP\",\"label\":\"alice\"},{\"secret\":\"KRSXG5A=\",\"label\":\"bob\"}]"

    @Test
    fun sealThenOpenReturnsTheSamePlaintext() {
        val blob = EncryptedBackupCodec.seal(password, plaintext)
        assertEquals(plaintext, EncryptedBackupCodec.open(password, blob, false))
    }

    @Test
    fun sealedBlobMatchesTheDocumentedFraming() {
        val blob = EncryptedBackupCodec.seal(password, plaintext)
        val header = EncryptedBackupHeader.parse(blob)

        // 4-byte iteration count, 12-byte salt, then the nonce-prefixed GCM blob (>= 12 + 16).
        assertTrue(blob.size >= Constants.INT_LENGTH + Constants.ENCRYPTION_IV_LENGTH +
                Constants.ENCRYPTION_IV_LENGTH + 16)
        assertTrue(header.getIterations() in
                Constants.PBKDF2_MIN_BACKUP_ITERATIONS..Constants.PBKDF2_MAX_BACKUP_ITERATIONS)
        assertEquals(Constants.ENCRYPTION_IV_LENGTH, header.getSalt().size)
    }

    @Test
    fun twoSealsProduceDifferentBlobsBecauseSaltAndNonceAreFresh() {
        val first = EncryptedBackupCodec.seal(password, plaintext)
        val second = EncryptedBackupCodec.seal(password, plaintext)
        assertFalse(first.contentEquals(second))
        // Both still open to the same plaintext despite the different framing.
        assertEquals(plaintext, EncryptedBackupCodec.open(password, first, false))
        assertEquals(plaintext, EncryptedBackupCodec.open(password, second, false))
    }

    @Test
    fun emptyPasswordIsRejectedBeforeAnyDerivation() {
        try {
            EncryptedBackupCodec.seal("", plaintext)
            throw AssertionError("empty password accepted")
        } catch (expected: IllegalArgumentException) {
            // required
        }
    }

    @Test
    fun wrongPasswordIsRejectedByTheGcmTag() {
        val blob = EncryptedBackupCodec.seal(password, plaintext)
        try {
            EncryptedBackupCodec.open("$password-wrong", blob, false)
            throw AssertionError("wrong password accepted")
        } catch (expected: GeneralSecurityException) {
            // BadPaddingException/AEADBadTagException from the failed authentication tag
        }
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val blob = EncryptedBackupCodec.seal(password, plaintext)
        val damaged = blob.copyOf()
        damaged[damaged.lastIndex] = (damaged[damaged.lastIndex].toInt() xor 1).toByte()
        try {
            EncryptedBackupCodec.open(password, damaged, false)
            throw AssertionError("tampered backup accepted")
        } catch (expected: GeneralSecurityException) {
            // authentication tag no longer matches
        }
    }

    @Test
    fun truncatedFramingIsRejectedBeforeDerivation() {
        val blob = EncryptedBackupCodec.seal(password, plaintext)
        val truncated = blob.copyOfRange(0, EncryptedBackupHeader.MIN_BACKUP_BYTES - 1)
        try {
            EncryptedBackupCodec.open(password, truncated, false)
            throw AssertionError("truncated backup accepted")
        } catch (expected: IllegalArgumentException) {
            // EncryptedBackupHeader framing guard
        }
    }

    @Test
    fun legacyHeaderlessSha256BackupsStillOpen() {
        // Reproduce the historical writer: SHA-256 password key and a nonce-prefixed GCM blob.
        val legacyKey = CryptoPrimitives.generateSymmetricKeyFromPassword(password)
        val legacyBlob = CryptoPrimitives.encrypt(
            legacyKey, plaintext.toByteArray(StandardCharsets.UTF_8))

        val opened = EncryptedBackupCodec.open(password, legacyBlob, true)
        assertEquals(plaintext, opened)
    }

    @Test
    fun legacyBackupRejectsTheWrongPassword() {
        val legacyKey = CryptoPrimitives.generateSymmetricKeyFromPassword(password)
        val legacyBlob = CryptoPrimitives.encrypt(
            legacyKey, plaintext.toByteArray(StandardCharsets.UTF_8))
        try {
            EncryptedBackupCodec.open("$password-wrong", legacyBlob, true)
            throw AssertionError("legacy backup accepted a wrong password")
        } catch (expected: GeneralSecurityException) {
            // required
        }
    }
}
