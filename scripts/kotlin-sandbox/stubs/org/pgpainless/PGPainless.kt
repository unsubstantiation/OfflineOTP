/*
 * Compile-only stand-in for the PGPainless 2.x entry point and its fluent builders. In 2.0.4 the
 * static encryptAndOrSign()/decryptAndOrVerify()/readKeyRing() helpers are deprecated; the supported
 * surface is the PGPainless instance from getInstance(), with generateMessage()/processMessage()
 * builders and the toKey()/toCertificate() converters for BC's OpenPGPKey/OpenPGPCertificate api.
 */
package org.pgpainless

import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.decryption_verification.DecryptionStream
import org.pgpainless.encryption_signing.EncryptionStream
import org.pgpainless.encryption_signing.ProducerOptions
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

class PGPainless private constructor() {
    fun generateMessage(): EncryptionBuilder =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

    fun processMessage(): DecryptionBuilder =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

    fun toKey(secretKeyRing: PGPSecretKeyRing): OpenPGPKey =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

    fun toCertificate(certificate: PGPPublicKeyRing): OpenPGPCertificate =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

    companion object {
        @JvmStatic
        fun getInstance(): PGPainless =
            throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
    }
}
