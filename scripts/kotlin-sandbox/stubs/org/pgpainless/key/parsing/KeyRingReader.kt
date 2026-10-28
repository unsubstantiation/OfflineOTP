/*
 * Compile-only stand-in for PGPainless 2.x's key ring reader. In 2.0.4 this class lives in
 * org.pgpainless.key.parsing (the older org.pgpainless.key.reading package no longer exists) and
 * returns nullable rings when the stream holds no matching material.
 */
package org.pgpainless.key.parsing

import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import java.io.InputStream

class KeyRingReader {
    fun publicKeyRing(inputStream: InputStream): PGPPublicKeyRing? =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

    fun secretKeyRing(inputStream: InputStream): PGPSecretKeyRing? =
        throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
}
