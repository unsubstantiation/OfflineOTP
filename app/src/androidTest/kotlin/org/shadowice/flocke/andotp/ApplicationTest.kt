/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * On-device vectors for the app's core crypto, AndroidKeyStore and database surfaces. The host
 * suite now exercises the same standard GCMParameterSpec crypto path, while the AndroidKeyStore
 * wrapper and real database transaction require an Android runtime. The calculator/document
 * vectors are repeated here so a device failure points at platform integration, not the algorithm.
 *
 * The suite is wired in app/build.gradle (androidx.test ext:junit/runner/core) and runs on
 * a connected device or emulator.
 */
package org.shadowice.flocke.andotp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import org.shadowice.flocke.andotp.Utilities.SecretKeyWrapper
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.Utilities.VaultCommitDecision
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class ApplicationTest {
    private val rfcSecret = "12345678901234567890".toByteArray(StandardCharsets.US_ASCII)

    @Test
    fun rfc6238TotpVectors() {
        assertEquals(94287082, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 59L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(7081804, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 1111111109L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(14050471, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 1111111111L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(89005924, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 1234567890L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(69279037, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 2000000000L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(65353130, TokenCalculator.TOTP_RFC6238(rfcSecret, 30, 20000000000L, 8, TokenCalculator.HashAlgorithm.SHA1))
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

    /**
     * The stored document format, byte-exact: field names, their order, the conditional
     * period/counter/pin fields and the re-encoded Base32 seed. An ordering or naming change
     * in EntryJson fails here (and in the host-side EntryJson vectors).
     */
    @Test
    fun testEntry() {
        val entry = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Example", "alice",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList(listOf("work")))
        assertEquals("""{"secret":"JBSWY3DPEHPK3PXP","issuer":"Example","label":"alice",""" +
            """"digits":6,"type":"TOTP","algorithm":"SHA1","thumbnail":"Default",""" +
            """"last_used":0,"used_frequency":0,"period":30,"tags":["work"]}""",
            entry.toJSON().toString())
    }

    @Test
    fun entryParsingAndSerializationRoundTrip() {
        val original = Entry(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", 7L, 6, "Example", "alice",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList(listOf("work")))
        val restored = Entry(original.toJSON())
        assertEquals(original, restored)
        assertEquals("JBSWY3DPEHPK3PXP", restored.getSecretEncoded())

        // Failures are IllegalArgumentException carrying only the error name, never the input.
        for (invalid in listOf(
            JSONObject("""{"secret":""}"""),
            JSONObject("""{"secret":"JBSWY3DPEHPK3PXP","label":"l","type":"hotp"}"""),
            "otpauth://totp/alice",
            "otpauth://hotp/alice?secret=JBSWY3DPEHPK3PXP"
        )) {
            try {
                if (invalid is JSONObject) Entry(invalid) else Entry(invalid as String)
                throw AssertionError("invalid input accepted")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    /**
     * A full vault round trip through the real transaction path. The test owns the key (a plain
     * AES key, not the KeyStore-wrapped one) and wipes the vault afterwards; it is skipped when
     * a vault already exists so it never destroys real data.
     */
    @Test
    fun databaseRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("a vault already exists; refusing to touch it",
            DatabaseHelper.canInitialize(context))

        try {
            val key: SecretKey = EncryptionHelper.generateSymmetricKey(EncryptionHelper.generateRandom(32))
            val settings = Settings(context)
            val initialized = DatabaseHelper.changeVaultEncryption(context, null, key, true) { transactionId ->
                VaultCommitDecision.fromCommit(settings.commitVaultSettings(
                    transactionId, Constants.EncryptionType.PASSWORD, null, null, true))
            }
            assertTrue("empty test vault initialization failed", initialized is VaultWriteResult.Saved)

            val first = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Example", "alice",
                TokenCalculator.HashAlgorithm.SHA1, ArrayList(listOf("work")))
            val second = Entry(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", 7L, 6, "Example", "bob",
                TokenCalculator.HashAlgorithm.SHA1, ArrayList())

            // saveDatabase destroys its argument list; keep expected copies for the comparison.
            val expected = listOf(first.toJSON().toString(), second.toJSON().toString())
            assertTrue(DatabaseHelper.saveDatabase(context, ArrayList(listOf(first, second)), key))

            val loaded = DatabaseHelper.loadDatabase(context, key)
            try {
                assertEquals(2, loaded.size)
                assertEquals(expected, loaded.map { it.toJSON().toString() })
            } finally {
                loaded.forEach { it.destroy() }
            }

            // A wrong key must not decrypt the vault.
            val wrongKey: SecretKey = EncryptionHelper.generateSymmetricKey(EncryptionHelper.generateRandom(32))
            var rejected = false
            try {
                DatabaseHelper.loadDatabase(context, wrongKey)
            } catch (expected2: Exception) {
                rejected = true
            }
            assertTrue("a wrong key decrypted the vault", rejected)
        } finally {
            DatabaseHelper.wipeDatabase(context)
        }
    }

    @Test
    fun aesGcmRoundTripWithTheAndroidProviders() {
        val key: SecretKey = EncryptionHelper.generateSymmetricKey(EncryptionHelper.generateRandom(32))
        val plaintext = EncryptionHelper.generateRandom(123)
        val ciphertext = EncryptionHelper.encrypt(key, plaintext)
        assertArrayEquals(plaintext, EncryptionHelper.decrypt(key, ciphertext))
        assertFalse(plaintext.contentEquals(ciphertext))

        // The nonce-prefixed framing: 12-byte nonce + ciphertext + 16-byte tag.
        assertEquals(Constants.ENCRYPTION_IV_LENGTH + plaintext.size + 16, ciphertext.size)
    }

    @Test
    fun aesGcmRejectsTamperedAndTruncatedFrames() {
        val key: SecretKey = EncryptionHelper.generateSymmetricKey(EncryptionHelper.generateRandom(32))
        val ciphertext = EncryptionHelper.encrypt(key, ByteArray(64))

        for (position in intArrayOf(0, ciphertext.size / 2, ciphertext.size - 1)) {
            val tampered = ciphertext.copyOf()
            tampered[position] = (tampered[position].toInt() xor 0x01).toByte()
            try {
                EncryptionHelper.decrypt(key, tampered)
                throw AssertionError("tampered frame accepted at byte $position")
            } catch (expected: AEADBadTagException) {
                // expected
            }
        }

        for (cut in intArrayOf(0, 15, 27)) {
            try {
                EncryptionHelper.decrypt(key, ciphertext.copyOf(cut))
                throw AssertionError("truncated frame accepted at $cut bytes")
            } catch (expected: IllegalArgumentException) {
                // expected: framing is rejected before any provider work
            }
        }
    }

    @Test
    fun androidKeyStoreWrappingKeyCanBeLoadedAndMissingAliasesFailClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "offlineotp-test-${UUID.randomUUID()}"
        val store = KeyStore.getInstance("AndroidKeyStore")
        try {
            assertNull("a fresh test alias must not exist", KeyStoreHelper.loadAsymmetricKeyPair(context, alias))
            val generated = KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(context, alias)
            assertNotNull("AndroidKeyStore did not create a wrapping key", generated)

            val raw = EncryptionHelper.generateRandom(32)
            val wrapped = SecretKeyWrapper(generated!!)
                .wrap(EncryptionHelper.generateSymmetricKey(raw))
            val loaded = KeyStoreHelper.loadAsymmetricKeyPair(context, alias)
            assertNotNull("read-only lookup lost the generated alias", loaded)
            assertArrayEquals(raw, SecretKeyWrapper(loaded!!).unwrap(wrapped).encoded)
        } finally {
            store.load(null)
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        }
        assertNull("a deleted alias must not silently regenerate", KeyStoreHelper.loadAsymmetricKeyPair(context, alias))
    }

    @Test
    fun encryptedBackupHeaderAndKeyDerivationAgree() {
        // PBKDF2 credential split and backup key derivation with a 12-byte backup salt.
        val salt = EncryptionHelper.generateRandom(12)
        val credentials = EncryptionHelper.generatePBKDF2Credentials("correct horse battery staple", salt, 1000)
        assertEquals(16, credentials.key.size)
        assertEquals(16, credentials.password.size)

        val key = EncryptionHelper.generateSymmetricKeyPBKDF2("correct horse battery staple", 1000, salt)
        val plaintext = EncryptionHelper.generateRandom(31)
        val sealed = EncryptionHelper.encrypt(key, plaintext)
        assertArrayEquals(plaintext, EncryptionHelper.decrypt(key, sealed))
        assertNotEquals(plaintext.size, 0)
    }
}
