/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 * Copyright (C) 2015 Bruno Bierbaumer
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Utilities

import android.content.Context

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyPair
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.InvalidKeySpecException
import java.util.Arrays
import java.util.function.BooleanSupplier
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.NoSuchPaddingException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Cryptographic helpers: AES-GCM encryption, PBKDF2 derivation, and KeyStore RSA key wrapping. */
object EncryptionHelper {
    /** PBKDF2-derived authentication password hash and symmetric encryption key. The portable
     *  derivation lives on [CryptoPrimitives]; this nested type keeps the app's pinned Java shape
     *  (public no-arg constructor with two mutable byte-array fields). */
    open class PBKDF2Credentials {
        @JvmField
        var password: ByteArray = ByteArray(0)

        @JvmField
        var key: ByteArray = ByteArray(0)
    }

    /** Error codes of [tryEncryptionChange]. */
    enum class EncryptionChangeResult {
        SUCCESS, CHANGE_FAILURE, BACKUP_FAILURE, MISSING_NEW_KEY, DECRYPTION_FAILED, TASK_CREATION_FAILED
    }

    /** Called once the database has been re-encrypted with the new key. */
    fun interface EncryptionChangeCallback {
        fun onSuccessfulEncryptionChange(newEncryptionType: Constants.EncryptionType, newEncryptionKey: SecretKey)
    }

    /** Re-encrypts only authenticated data; staged settings/file commit survives process death. */
    @JvmStatic
    fun tryEncryptionChange(context: Context, oldKey: SecretKey?, newType: Constants.EncryptionType, newKey: ByteArray?,
                            callback: EncryptionChangeCallback?): EncryptionChangeResult =
        tryEncryptionChange(context, oldKey, newType, newKey, callback, BooleanSupplier { true })

    /**
     * Staged re-encryption with a cancellation/permission gate. The guard order is load-bearing:
     * the caller's permission and the live session key are checked before the vault is read, and
     * again (with the generation) before the staged metadata commit decides to commit or cancel.
     */
    @JvmStatic
    fun tryEncryptionChange(context: Context, oldKey: SecretKey?, newType: Constants.EncryptionType, newKey: ByteArray?,
                            callback: EncryptionChangeCallback?, permitted: BooleanSupplier): EncryptionChangeResult {
        synchronized(DatabaseHelper.DatabaseFileLock) {
            if (!permitted.getAsBoolean() || oldKey == null || VaultSession.getKey() !== oldKey)
                return EncryptionChangeResult.DECRYPTION_FAILED
            val loaded = DatabaseHelper.readVault(context, oldKey)
            if (loaded !is VaultReadResult.Loaded) return EncryptionChangeResult.DECRYPTION_FAILED
            loaded.close()
            val key: SecretKey? = when {
                newType == Constants.EncryptionType.KEYSTORE -> KeyStoreHelper.createEncryptionKey(context)
                newKey != null && (newKey.size == 16 || newKey.size == 24 || newKey.size == 32) -> generateSymmetricKey(newKey)
                else -> return EncryptionChangeResult.MISSING_NEW_KEY
            }
            if (key == null) return EncryptionChangeResult.MISSING_NEW_KEY
            val settings = Settings(context)
            val generation = VaultSession.generation()
            val write = DatabaseHelper.changeVaultEncryption(context, oldKey, key, false) { id ->
                if (!permitted.getAsBoolean() || !VaultSession.isCurrent(generation)) VaultCommitDecision.CANCELED
                else VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, newType, null, null, false))
            }
            if (write !is VaultWriteResult.Saved) return EncryptionChangeResult.CHANGE_FAILURE
            callback?.onSuccessfulEncryptionChange(newType, key)
            return EncryptionChangeResult.SUCCESS
        }
    }

    /** Measures PBKDF2 speed and scales the iteration count to the target auth time (never below the minimum). */
    @JvmStatic
    fun benchmarkIterations(password: String, salt: ByteArray): Int {
        return try {
            val secretKeyFactory = SecretKeyFactory.getInstance(Constants.PBKDF2_ALGORITHM)
            val passwordChars = password.toCharArray()
            val keySpec: PBEKeySpec
            try {
                keySpec = PBEKeySpec(passwordChars, salt, Constants.PBKDF2_BENCHMARK_ITERATIONS, Constants.PBKDF2_LENGTH)
            } finally {
                Arrays.fill(passwordChars, '\u0000')
            }

            val startTime = System.currentTimeMillis()
            try {
                secretKeyFactory.generateSecret(keySpec)
            } finally {
                keySpec.clearPassword()
            }
            val finishTime = System.currentTimeMillis()

            val timeDiff = Math.max(1L, finishTime - startTime)
            val scaledIterationTarget = ((Constants.PBKDF2_BENCHMARK_ITERATIONS.toDouble() / timeDiff.toDouble())
                    * Constants.PBKDF2_TARGET_AUTH_TIME).toInt()

            Math.min(2000000, Math.max(scaledIterationTarget, Constants.PBKDF2_MIN_AUTH_ITERATIONS))
        } catch (error: NoSuchAlgorithmException) {
            error.printStackTrace()
            Constants.PBKDF2_MIN_AUTH_ITERATIONS
        } catch (error: InvalidKeySpecException) {
            error.printStackTrace()
            Constants.PBKDF2_MIN_AUTH_ITERATIONS
        }
    }

    /** Random iteration count for new encrypted backups, so not all backups share one value. Pure-JVM implementation lives in [CryptoPrimitives]. */
    @JvmStatic
    fun generateRandomIterations(): Int = CryptoPrimitives.generateRandomIterations()

    /** Returns [length] random bytes from [SecureRandom]. Pure-JVM implementation lives in [CryptoPrimitives]. */
    @JvmStatic
    fun generateRandom(length: Int): ByteArray = CryptoPrimitives.generateRandom(length)

    /** Derives a PBKDF2 output and splits it into [PBKDF2Credentials.password] and [PBKDF2Credentials.key].
     *  The portable derivation lives in [CryptoPrimitives]; the nested return type keeps callers unchanged. */
    @JvmStatic
    @Throws(NoSuchAlgorithmException::class, InvalidKeySpecException::class)
    fun generatePBKDF2Credentials(password: String?, salt: ByteArray?, iter: Int): PBKDF2Credentials {
        val derived = CryptoPrimitives.generatePBKDF2Credentials(password, salt, iter)
        return PBKDF2Credentials().apply {
            this.password = derived.password
            this.key = derived.key
        }
    }

    /** Wraps raw bytes in an AES [SecretKey]. */
    @JvmStatic
    fun generateSymmetricKey(data: ByteArray): SecretKey = SecretKeySpec(data, 0, data.size, "AES")

    /**
     * Derives the AES key used for password-encrypted backups. Same credential bounds as
     * [generatePBKDF2Credentials].
     */
    @JvmStatic
    @Throws(NoSuchAlgorithmException::class, InvalidKeySpecException::class)
    fun generateSymmetricKeyPBKDF2(password: String?, iter: Int, salt: ByteArray?): SecretKey =
        CryptoPrimitives.generateSymmetricKeyPBKDF2(password, iter, salt)

    /** Legacy key derivation (plain SHA-256 of the password) for old-format encrypted backups. Pure-JVM implementation lives in [CryptoPrimitives]. */
    @JvmStatic
    @Throws(NoSuchAlgorithmException::class)
    fun generateSymmetricKeyFromPassword(password: String): SecretKey =
        CryptoPrimitives.generateSymmetricKeyFromPassword(password)

    /** Encrypts with an explicit nonce and the 128-bit GCM tag used by existing vault/backup files. */
    @JvmStatic
    @Throws(NoSuchPaddingException::class, NoSuchAlgorithmException::class, InvalidAlgorithmParameterException::class,
            InvalidKeyException::class, BadPaddingException::class, IllegalBlockSizeException::class)
    fun encrypt(secretKey: SecretKey, iv: IvParameterSpec, plainText: ByteArray): ByteArray =
        CryptoPrimitives.encrypt(secretKey, iv, plainText)

    /** Encrypts and prefixes the random GCM nonce, as used in the database and backup formats. */
    @JvmStatic
    @Throws(NoSuchPaddingException::class, BadPaddingException::class, InvalidKeyException::class,
            NoSuchAlgorithmException::class, IllegalBlockSizeException::class, InvalidAlgorithmParameterException::class)
    fun encrypt(secretKey: SecretKey, plaintext: ByteArray): ByteArray =
        CryptoPrimitives.encrypt(secretKey, plaintext)

    /** RSA-encrypts a small secret (e.g. the stored backup password) with a KeyStore public key. */
    @JvmStatic
    @Throws(NoSuchPaddingException::class, BadPaddingException::class, InvalidKeyException::class,
            NoSuchAlgorithmException::class, IllegalBlockSizeException::class)
    fun encrypt(publicKey: PublicKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(Constants.ALGORITHM_ASYMMETRIC)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)

        return cipher.doFinal(plaintext)
    }

    /** Decrypts with an explicit nonce and the 128-bit GCM tag used by existing vault/backup files. Pure-JVM implementation lives in [CryptoPrimitives]. */
    @JvmStatic
    @Throws(NoSuchPaddingException::class, InvalidKeyException::class, NoSuchAlgorithmException::class,
            IllegalBlockSizeException::class, BadPaddingException::class, InvalidAlgorithmParameterException::class)
    fun decrypt(secretKey: SecretKey, iv: IvParameterSpec, cipherText: ByteArray): ByteArray =
        CryptoPrimitives.decrypt(secretKey, iv, cipherText)

    /** Splits the 12-byte GCM nonce off the front of [cipherText] and decrypts the remaining ciphertext. */
    @JvmStatic
    @Throws(NoSuchPaddingException::class, InvalidKeyException::class, NoSuchAlgorithmException::class,
            IllegalBlockSizeException::class, BadPaddingException::class, InvalidAlgorithmParameterException::class)
    fun decrypt(secretKey: SecretKey, cipherText: ByteArray?): ByteArray =
        CryptoPrimitives.decrypt(secretKey, cipherText)

    /** RSA decryption, the counterpart of [encrypt]. */
    @JvmStatic
    @Throws(NoSuchPaddingException::class, InvalidKeyException::class, NoSuchAlgorithmException::class,
            IllegalBlockSizeException::class, BadPaddingException::class)
    fun decrypt(privateKey: PrivateKey, cipherText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(Constants.ALGORITHM_ASYMMETRIC)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)

        return cipher.doFinal(cipherText)
    }

    /** Loads (or generates and wraps) the symmetric vault key using [keyPair]. */
    @JvmStatic
    @Throws(GeneralSecurityException::class, IOException::class)
    fun loadOrGenerateWrappedKey(keyFile: File, keyPair: KeyPair): SecretKey {
        val wrapper = SecretKeyWrapper(keyPair)

        // Generate secret key if none exists
        if (!keyFile.exists()) {
            val raw = generateRandom(Constants.ENCRYPTION_KEY_LENGTH)

            val key = SecretKeySpec(raw, "AES")
            val wrapped = wrapper.wrap(key)

            FileHelper.writeBytesToFile(keyFile, wrapped)
        }

        // Even if we just generated the key, always read it back to ensure we
        // can read it successfully.
        val wrapped = FileHelper.readFileToBytes(keyFile)

        return wrapper.unwrap(wrapped)
    }
}
