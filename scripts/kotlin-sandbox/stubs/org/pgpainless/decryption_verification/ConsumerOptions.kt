/*
 * Compile-only stand-ins for the PGPainless high-level decryption API.
 */
package org.pgpainless.decryption_verification

import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.pgpainless.key.protection.SecretKeyRingProtector
import java.io.InputStream

open class ConsumerOptions protected constructor() {
    fun addDecryptionKey(keys: PGPSecretKeyRing): ConsumerOptions =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

    fun addDecryptionKey(keys: PGPSecretKeyRing, protector: SecretKeyRingProtector): ConsumerOptions =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

    companion object {
        @JvmStatic
        fun get(): ConsumerOptions =
            throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
    }
}

/** Emits the decrypted bytes when read; the stub exists only for type resolution. */
open class DecryptionStream : InputStream() {
    override fun read(): Int =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
}
