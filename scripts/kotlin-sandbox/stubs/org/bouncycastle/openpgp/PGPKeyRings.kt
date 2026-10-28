/*
 * Compile-only stand-ins for the BouncyCastle key ring types referenced by the desktop
 * PGPainless adapter. The real classes arrive transitively through pgpainless-core in the Gradle
 * build; the no-network sandbox only needs the type names to compile the adapter. No behavior is
 * exercised here, and these stubs are on the desktop gate's classpath only, never :core's.
 */
package org.bouncycastle.openpgp

open class PGPPublicKeyRing

open class PGPSecretKeyRing
