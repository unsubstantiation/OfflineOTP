/*
 * Compile-only stand-ins for the BouncyCastle 1.84 `org.bouncycastle.openpgp.api` marker types
 * that PGPainless 2.x uses in its high-level API (OpenPGPKey for secret keys, OpenPGPCertificate
 * for public keys). The real interfaces arrive transitively through pgpainless-core in the Gradle
 * build; the no-network sandbox only needs the type names so the desktop adapter compiles against
 * the same API shape a real build resolves. Desktop gate classpath only, never :core.
 */
package org.bouncycastle.openpgp.api

/** BC 1.84 secret-key handle; produced by PGPainless.toKey(PGPSecretKeyRing). */
interface OpenPGPKey

/** BC 1.84 public-key/certificate handle; produced by PGPainless.toCertificate(PGPPublicKeyRing). */
interface OpenPGPCertificate
