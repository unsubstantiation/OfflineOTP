/*
 * Compile-only stand-in for the PGPainless key ring reader.
 */
package org.pgpainless.key.reading

import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import java.io.InputStream

interface KeyRingReader {
    fun publicKeyRing(inputStream: InputStream): PGPPublicKeyRing
    fun secretKeyRing(inputStream: InputStream): PGPSecretKeyRing
}
