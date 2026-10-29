/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the shared password-encrypted backup codec. These pin the on-disk framing
 * (iterations + salt + nonce-prefixed AES-GCM) that makes encrypted backups interchangeable
 * between the Android app and the desktop client.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedBackupCodecTest {

    private val password = "backup password ! with punctuation"
    private val plaintext =
        "[{\"secret\":\"JBSWY3DPEHPK3PXP\",\"label\":\"alice\"},{\"secret\":\"KRSXG5A=\",\"label\":\"bob\"}]"

    @Test
    fun sealThenOpenReturnsTheSamePlaintext() {
        val blob = EncryptedBackupCodec.seal(password, plaintext)
        assertEquals(plaintext, EncryptedBackupCodec.open(password, blob))
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
        assertEquals(plaintext, EncryptedBackupCodec.open(password, first))
        assertEquals(plaintext, EncryptedBackupCodec.open(password, second))
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
            EncryptedBackupCodec.open("$password-wrong", blob)
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
            EncryptedBackupCodec.open(password, damaged)
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
            EncryptedBackupCodec.open(password, truncated)
            throw AssertionError("truncated backup accepted")
        } catch (expected: IllegalArgumentException) {
            // EncryptedBackupHeader framing guard
        }
    }

    @Test
    fun headerlessSha256BackupWithOutOfRangePrefixIsRejected() {
        val blob = headerlessBackup(0x40414243)
        try {
            EncryptedBackupCodec.open(password, blob)
            throw AssertionError("headerless backup accepted")
        } catch (expected: IllegalArgumentException) {
            // A typical nonce does not contain a valid framed-backup iteration count.
        }
    }

    @Test
    fun headerlessSha256BackupIsRejectedEvenWhenItsNonceLooksLikeAHeader() {
        val blob = headerlessBackup(1_000)
        assertEquals(1_000, EncryptedBackupHeader.parse(blob).getIterations())
        try {
            EncryptedBackupCodec.open(password, blob)
            throw AssertionError("headerless backup accepted")
        } catch (expected: GeneralSecurityException) {
            // A plausible iteration count passes framing, but must not trigger a legacy fallback.
        }
    }

    /** Creates a valid pre-0.6.3 frame only for negative tests, independently of the codec. */
    private fun headerlessBackup(noncePrefix: Int): ByteArray {
        val nonce = ByteBuffer.allocate(12).putInt(noncePrefix).putLong(0x1020304050607080L).array()
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray(Charsets.UTF_8)), "AES")
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
        encrypt.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val ciphertext = encrypt.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Verify that this is an authentic legacy ciphertext, not merely malformed random bytes.
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
        decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        assertEquals(plaintext, String(decrypt.doFinal(ciphertext), Charsets.UTF_8))
        return nonce + ciphertext
    }
}
