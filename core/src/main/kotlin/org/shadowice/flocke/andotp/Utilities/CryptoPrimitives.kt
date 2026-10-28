package org.shadowice.flocke.andotp.Utilities

import java.nio.charset.StandardCharsets
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.spec.InvalidKeySpecException
import java.util.Arrays
import java.util.Random
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.NoSuchPaddingException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM cryptographic primitives shared by the Android app and the desktop client.
 *
 * These deliberately use only portable JCA contracts (no [android.content.Context], KeyStore, or
 * platform provider), so they can live in the Android-free `:core` module. [EncryptionHelper] keeps
 * its identical static surface and delegates here; the JCA calls and their ordering are unchanged,
 * so derived keys and ciphertext bytes are byte-identical to the previous implementation.
 */
object CryptoPrimitives {
    /** Returns [length] random bytes from [SecureRandom]. */
    @JvmStatic
    fun generateRandom(length: Int): ByteArray {
        val raw = ByteArray(length)
        SecureRandom().nextBytes(raw)

        return raw
    }

    /**
     * Derives the AES key used for password-encrypted backups. Same credential bounds as the
     * app-side PBKDF2 factories.
     */
    @JvmStatic
    @Throws(NoSuchAlgorithmException::class, InvalidKeySpecException::class)
    fun generateSymmetricKeyPBKDF2(password: String?, iter: Int, salt: ByteArray?): SecretKey {
        if (password == null || password.length > InputPolicy.MAX_TEXT_CHARS || salt == null
            || salt.size < 8 || iter < 1 || iter > 20000000)
            throw IllegalArgumentException("Invalid credential parameters")
        val secretKeyFactory = SecretKeyFactory.getInstance(Constants.PBKDF2_ALGORITHM)
        val passwordChars = password.toCharArray()
        val keySpec: PBEKeySpec
        try {
            keySpec = PBEKeySpec(passwordChars, salt, iter, Constants.PBKDF2_LENGTH)
        } finally {
            Arrays.fill(passwordChars, '\u0000')
        }

        val raw: ByteArray
        try {
            raw = secretKeyFactory.generateSecret(keySpec).encoded
        } finally {
            keySpec.clearPassword()
        }
        try {
            return SecretKeySpec(raw, "AES")
        } finally {
            Arrays.fill(raw, 0.toByte())
        }
    }

    /** Encrypts with an explicit nonce and the 128-bit GCM tag used by existing vault/backup files. */
    @JvmStatic
    @Throws(NoSuchPaddingException::class, NoSuchAlgorithmException::class, InvalidAlgorithmParameterException::class,
        InvalidKeyException::class, BadPaddingException::class, IllegalBlockSizeException::class)
    fun encrypt(secretKey: SecretKey, iv: IvParameterSpec, plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(Constants.ALGORITHM_SYMMETRIC)
        // GCMParameterSpec is the portable JCA contract. Some Android providers accept an
        // IvParameterSpec as a legacy extension, but desktop SunJCE correctly rejects it.
        // Keep the public overload for source/binary compatibility and translate its nonce here.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv.iv))

        return cipher.doFinal(plainText)
    }

    /** Encrypts and prefixes the random GCM nonce, as used in the database and backup formats. */
    @JvmStatic
    @Throws(NoSuchPaddingException::class, BadPaddingException::class, InvalidKeyException::class,
        NoSuchAlgorithmException::class, IllegalBlockSizeException::class, InvalidAlgorithmParameterException::class)
    fun encrypt(secretKey: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(Constants.ENCRYPTION_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipherText = encrypt(secretKey, IvParameterSpec(iv), plaintext)

        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

        return combined
    }

    /** Decrypts with an explicit nonce and the 128-bit GCM tag, matching [encrypt]'s IV overload. */
    @JvmStatic
    @Throws(NoSuchPaddingException::class, NoSuchAlgorithmException::class, InvalidAlgorithmParameterException::class,
        InvalidKeyException::class, BadPaddingException::class, IllegalBlockSizeException::class)
    fun decrypt(secretKey: SecretKey, iv: IvParameterSpec, cipherText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(Constants.ALGORITHM_SYMMETRIC)
        // See encrypt(): normalize the compatibility overload to the standard GCM parameters.
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv.iv))

        return cipher.doFinal(cipherText)
    }

    /** Decrypts a nonce-prefixed AES-GCM blob produced by [encrypt]. */
    @JvmStatic
    @Throws(NoSuchPaddingException::class, BadPaddingException::class, InvalidKeyException::class,
        NoSuchAlgorithmException::class, IllegalBlockSizeException::class, InvalidAlgorithmParameterException::class)
    fun decrypt(secretKey: SecretKey, cipherText: ByteArray?): ByteArray {
        // AES-GCM always contains a nonce and a 128-bit authentication tag,
        // even when the plaintext is empty. Reject framing before provider work.
        if (cipherText == null || cipherText.size < Constants.ENCRYPTION_IV_LENGTH + 16) {
            throw IllegalArgumentException("Truncated AES-GCM data")
        }
        val iv = Arrays.copyOfRange(cipherText, 0, Constants.ENCRYPTION_IV_LENGTH)
        val encrypted = Arrays.copyOfRange(cipherText, Constants.ENCRYPTION_IV_LENGTH, cipherText.size)

        return decrypt(secretKey, IvParameterSpec(iv), encrypted)
    }

    /** Legacy key derivation (plain SHA-256 of the password) for old-format encrypted backups. */
    @JvmStatic
    @Throws(NoSuchAlgorithmException::class)
    fun generateSymmetricKeyFromPassword(password: String): SecretKey {
        val sha = MessageDigest.getInstance("SHA-256")

        return SecretKeySpec(sha.digest(password.toByteArray(StandardCharsets.UTF_8)), "AES")
    }

    /**
     * Selects the PBKDF2 iteration count for a new password backup, uniformly across the configured
     * backup range. The random source and bounds match the Android implementation exactly.
     */
    @JvmStatic
    fun generateRandomIterations(): Int {
        val rand = Random()
        return rand.nextInt((Constants.PBKDF2_MAX_BACKUP_ITERATIONS - Constants.PBKDF2_MIN_BACKUP_ITERATIONS) + 1) +
                Constants.PBKDF2_MIN_BACKUP_ITERATIONS
    }
}
