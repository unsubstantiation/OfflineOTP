/*
 * Compile-only stand-ins for the PGPainless high-level encryption API.
 */
package org.pgpainless.encryption_signing

import org.bouncycastle.openpgp.PGPPublicKeyRing
import java.io.OutputStream

open class EncryptionOptions protected constructor() {
    fun addRecipient(certificate: PGPPublicKeyRing): EncryptionOptions =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

    companion object {
        @JvmStatic
        fun encryptCommunications(): EncryptionOptions =
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
