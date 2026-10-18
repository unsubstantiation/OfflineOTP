/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.AlgorithmParameterSpec
import java.util.Calendar
import java.util.GregorianCalendar
import javax.crypto.SecretKey
import javax.security.auth.x500.X500Principal

/** Manages the Android KeyStore RSA key pair that wraps the AES vault key in KeyStore mode. */
object KeyStoreHelper {

    /** Deletes the wrapped key file and the KeyStore entry (PANIC response and "clear keystore"). */
    @JvmStatic
    fun wipeKeys(context: Context) {
        VaultSession.lock(SessionLockReason.RESET)
        synchronized(DatabaseHelper.DatabaseFileLock) {
            FileHelper.delete(File(context.getFilesDir(), Constants.FILENAME_ENCRYPTED_KEY))
            try {
                val store = KeyStore.getInstance("AndroidKeyStore")
                store.load(null)
                for (alias in arrayOf(Constants.KEYSTORE_ALIAS_WRAPPING, Constants.KEYSTORE_ALIAS_PASSWORD)) {
                    if (store.containsAlias(alias)) store.deleteEntry(alias)
                }
            } catch (_: GeneralSecurityException) {
                // No replacement keys on error.
            } catch (_: IOException) {
                // No replacement keys on error.
            } catch (_: RuntimeException) {
                // No replacement keys on error.
            }
        }
    }

    /** Read-only lookup of the KeyStore RSA key pair; returns null if the alias is absent. */
    @JvmStatic
    @Throws(GeneralSecurityException::class, IOException::class)
    fun loadAsymmetricKeyPair(context: Context, alias: String): KeyPair? {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (!store.containsAlias(alias)) return null
        val entry = store.getEntry(alias, null) as KeyStore.PrivateKeyEntry?
        return if (entry == null) null else KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    /** Returns the RSA key pair stored under the alias, generating it first if needed. */
    @JvmStatic
    @Throws(GeneralSecurityException::class, IOException::class)
    fun loadOrGenerateAsymmetricKeyPair(context: Context, alias: String): KeyPair? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        if (!keyStore.containsAlias(alias)) {
            val start = GregorianCalendar()
            val end = GregorianCalendar()
            end.add(Calendar.YEAR, 100)

            val spec: AlgorithmParameterSpec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setCertificateSubject(X500Principal("CN=$alias"))
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(start.time)
                    .setCertificateNotAfter(end.time)
                    .build()

            val gen = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")

            gen.initialize(spec)
            gen.generateKeyPair()
        }

        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry?

        return if (entry != null) KeyPair(entry.certificate.publicKey, entry.privateKey) else null
    }

    /** Read-only key lookup. Missing files/aliases never generate replacements. */
    @JvmStatic
    fun loadEncryptionKeyFromKeyStore(context: Context, failSilent: Boolean): SecretKey? {
        synchronized(DatabaseHelper.DatabaseFileLock) {
            try {
                val wrapped = File(context.getFilesDir(), Constants.FILENAME_ENCRYPTED_KEY)
                if (!FileHelper.exists(wrapped)) return null
                val pair = loadAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_WRAPPING) ?: return null
                return SecretKeyWrapper(pair).unwrap(FileHelper.readFileToBytes(wrapped))
            } catch (_: GeneralSecurityException) {
                return null
            } catch (_: IOException) {
                return null
            } catch (_: RuntimeException) {
                return null
            }
        }
    }

    /** Only explicit new-vault/reset/migration paths may call this. */
    @JvmStatic
    fun createEncryptionKey(context: Context): SecretKey? {
        synchronized(DatabaseHelper.DatabaseFileLock) {
            val wrapped = File(context.getFilesDir(), Constants.FILENAME_ENCRYPTED_KEY)
            if (FileHelper.exists(wrapped)) return loadEncryptionKeyFromKeyStore(context, true)
            try {
                val pair = loadOrGenerateAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_WRAPPING) ?: return null
                return EncryptionHelper.loadOrGenerateWrappedKey(wrapped, pair)
            } catch (_: GeneralSecurityException) {
                return null
            } catch (_: IOException) {
                return null
            } catch (_: RuntimeException) {
                return null
            }
        }
    }
}
