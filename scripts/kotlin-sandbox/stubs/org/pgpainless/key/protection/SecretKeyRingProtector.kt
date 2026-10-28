/*
 * Compile-only stand-in for PGPainless' secret key protection surface. See the BouncyCastle stub
 * header: the real library is declared in desktop/build.gradle and only the sandbox lacks it.
 */
package org.pgpainless.key.protection

import org.bouncycastle.openpgp.PGPSecretKeyRing

interface SecretKeyRingProtector

class PasswordProtector private constructor() : SecretKeyRingProtector {
    class Builder {
        fun withPassphrase(passphrase: CharArray): SecretKeyRingProtector =
            throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
    }

    companion object {
        @JvmStatic
        fun forKey(keys: PGPSecretKeyRing): Builder =
            throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
    }
}
