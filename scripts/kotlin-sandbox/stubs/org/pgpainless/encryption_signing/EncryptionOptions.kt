/*
 * Compile-only stand-ins for the PGPainless 2.x high-level encryption API. In 2.0.4 recipients are
 * BC OpenPGPCertificate handles and the options object is scoped to a PGPainless api instance.
 */
package org.pgpainless.encryption_signing

import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.pgpainless.PGPainless
import java.io.OutputStream

open class EncryptionOptions protected constructor(@Suppress("unused") private val api: PGPainless) {
    fun addRecipient(certificate: OpenPGPCertificate): EncryptionOptions =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

    companion object {
        @JvmStatic
        fun encryptCommunications(api: PGPainless): EncryptionOptions =
            throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
    }
}

class ProducerOptions private constructor(private val encryption: EncryptionOptions) {
    fun encryptionOptions(): EncryptionOptions = encryption

    companion object {
        @JvmStatic
        fun encrypt(options: EncryptionOptions): ProducerOptions =
            throw NotImplementedError("sandbox stream; real PGPainless runs under Gradle")
    }
}

/** Finalizes the OpenPGP message when closed; the stub exists only for type resolution. */
open class EncryptionStream : OutputStream() {
    override fun write(b: Int) {
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
    }
}
