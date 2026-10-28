/*
 * Compile-only stand-in for PGPainless' secret key protection surface. In 2.0.4 the old
 * PasswordProtector class is gone: callers build a protector from an org.pgpainless.util.Passphrase
 * through the companion factories below (unlockAnyKeyWith for one passphrase over every key,
 * unprotectedKeys for unencrypted secret keys).
 */
package org.pgpainless.key.protection

import org.pgpainless.util.Passphrase

interface SecretKeyRingProtector {
    companion object {
        @JvmStatic
        fun unprotectedKeys(): SecretKeyRingProtector =
            throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")

        @JvmStatic
        fun unlockAnyKeyWith(passphrase: Passphrase): SecretKeyRingProtector =
            throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
    }
}
