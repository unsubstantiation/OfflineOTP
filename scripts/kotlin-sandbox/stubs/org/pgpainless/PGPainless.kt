/*
 * Compile-only stand-in for the PGPainless entry point and its fluent builders. Only the methods
 * the desktop OpenPGP adapter actually calls are modeled; the Gradle build supplies the library.
 */
package org.pgpainless

import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.decryption_verification.DecryptionStream
import org.pgpainless.encryption_signing.EncryptionStream
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.key.reading.KeyRingReader
import java.io.InputStream
import java.io.OutputStream

interface EncryptionBuilderOnStream {
    fun withOptions(options: ProducerOptions): EncryptionStream
}

interface EncryptionBuilder {
    fun onOutputStream(outputStream: OutputStream): EncryptionBuilderOnStream
}

interface DecryptionBuilderOnStream {
    fun withOptions(options: ConsumerOptions): DecryptionStream
}

interface DecryptionBuilder {
    fun onInputStream(inputStream: InputStream): DecryptionBuilderOnStream
}

object PGPainless {
    @JvmStatic
    fun encryptAndOrSign(): EncryptionBuilder =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

    @JvmStatic
    fun decryptAndOrVerify(): DecryptionBuilder =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

    @JvmStatic
    fun readKeyRing(): KeyRingReader =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
}
