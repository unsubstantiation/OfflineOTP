/*
 * Compile-only stand-in for org.pgpainless.util.Passphrase, the 2.x replacement for the removed
 * PasswordProtector builder. Secret key protectors are built from Passphrase instances via
 * SecretKeyRingProtector.unlockAnyKeyWith(...).
 */
package org.pgpainless.util

class Passphrase private constructor(@Suppress("unused") private val chars: CharArray) {
    companion object {
        @JvmStatic
        fun fromPassword(password: CharSequence): Passphrase =
            throw NotImplementedError("sandbox stub; real PGPainless runs under Gradle")
    }
}
