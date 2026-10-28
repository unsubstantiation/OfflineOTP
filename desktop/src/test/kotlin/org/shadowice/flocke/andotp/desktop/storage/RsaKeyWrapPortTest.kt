/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the desktop RSA key-wrap port, the software counterpart of Android's
 * KeyStore-backed SecretKeyWrapper. Real JCA runs in the sandbox, so wrap/unwrap round trips and
 * key-pair persistence are exercised end to end.
 */
package org.shadowice.flocke.andotp.desktop.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.nio.file.Files

class RsaKeyWrapPortTest {

    private fun newDirectory() = Files.createTempDirectory("offlineotp-rsa-").toFile()

    private fun aesKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun wrappedKeyRoundTripsThroughRsaPkcs1() {
        val port = RsaKeyWrapPort(newDirectory())
        val original = aesKey()

        val wrapped = port.wrap(original)
        val recovered = port.unwrap(wrapped)

        assertEquals("AES", recovered.algorithm)
        assertArrayEquals(original.encoded, recovered.encoded)
        // RSA PKCS#1 padding randomizes the wrapped blob per call.
        val wrappedAgain = port.wrap(original)
        assertFalse(wrapped.contentEquals(wrappedAgain))
        assertArrayEquals(original.encoded, port.unwrap(wrappedAgain).encoded)
    }

    @Test
    fun keyPairIsPersistedAndReusedByANewInstance() {
        val directory = newDirectory()
        assertFalse(RsaKeyWrapPort.isProvisioned(directory))

        val first = RsaKeyWrapPort(directory)
        val key = aesKey()
        val wrapped = first.wrap(key)

        assertTrue(RsaKeyWrapPort.isProvisioned(directory))
        assertTrue(File(directory, RsaKeyWrapPort.PRIVATE_KEY_FILE).exists())
        assertTrue(File(directory, RsaKeyWrapPort.PUBLIC_KEY_FILE).exists())

        // A second port over the same directory loads the persisted pair instead of regenerating.
        val second = RsaKeyWrapPort(directory)
        assertArrayEquals(key.encoded, second.unwrap(wrapped).encoded)
    }

    @Test
    fun ownerOnlyPermissionsAreRequestedWhereTheFileSystemSupportsThem() {
        val directory = newDirectory()
        RsaKeyWrapPort(directory).wrap(SecretKeySpec(ByteArray(16), "AES"))

        val privateKey = File(directory, RsaKeyWrapPort.PRIVATE_KEY_FILE)
        try {
            val permissions = Files.getPosixFilePermissions(privateKey.toPath())
            assertTrue(permissions.none {
                it.name.startsWith("OTHER") || it.name.startsWith("GROUP")
            })
        } catch (expected: UnsupportedOperationException) {
            // Non-POSIX file systems use the JDK owner-only fallback.
        }
    }
}
