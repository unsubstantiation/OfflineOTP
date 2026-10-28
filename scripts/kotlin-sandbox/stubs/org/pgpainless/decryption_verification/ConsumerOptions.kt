/*
 * Compile-only stand-ins for the PGPainless 2.x high-level decryption API. In 2.0.4 decryption keys
 * are BC OpenPGPKey handles paired with a SecretKeyRingProtector; the PGPSecretKeyRing overload is
 * deprecated and no longer used by the desktop adapter.
 */
package org.pgpainless.decryption_verification

import org.bouncycastle.openpgp.api.OpenPGPKey
import org.pgpainless.PGPainless
import org.pgpainless.key.protection.SecretKeyRingProtector
import java.io.InputStream

open class ConsumerOptions(@Suppress("unused") private val api: PGPainless) {
    fun addDecryptionKey(key: OpenPGPKey, protector: SecretKeyRingProtector): ConsumerOptions =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
}

/** Emits the decrypted bytes when read; the stub exists only for type resolution. */
open class DecryptionStream : InputStream() {
    override fun read(): Int =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
}
