/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for RSA key wrapping with an in-memory key pair (no Android KeyStore involved).
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class SecretKeyWrapperTest {
    private fun freshPair(): java.security.KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun wrappedKeysRoundTrip() {
        val wrapper = SecretKeyWrapper(freshPair())
        val raw = EncryptionHelper.generateRandom(32)
        val key: SecretKey = SecretKeySpec(raw, "AES")

        val wrapped = wrapper.wrap(key)
        val unwrapped = wrapper.unwrap(wrapped)
        assertEquals("AES", unwrapped.algorithm)
        assertArrayEquals(raw, unwrapped.encoded)
    }

    @Test
    fun wrappingHidesTheKey() {
        val wrapper = SecretKeyWrapper(freshPair())
        val raw = EncryptionHelper.generateRandom(32)
        val wrapped = wrapper.wrap(SecretKeySpec(raw, "AES"))
        assertEquals(false, wrapped.contentEquals(raw))
        assertEquals(true, wrapped.size > raw.size)  // RSA padding overhead
    }

    @Test
    fun tamperedBlobsAreRejected() {
        val wrapper = SecretKeyWrapper(freshPair())
        val wrapped = wrapper.wrap(SecretKeySpec(EncryptionHelper.generateRandom(32), "AES"))

        for (position in intArrayOf(0, wrapped.size / 2, wrapped.size - 1)) {
            val tampered = wrapped.copyOf()
            tampered[position] = (tampered[position].toInt() xor 0x40).toByte()
            try {
                wrapper.unwrap(tampered)
                throw AssertionError("tampered wrapped key accepted at byte $position")
            } catch (expected: Exception) {
                // expected: GeneralSecurityException from the RSA unwrap
            }
        }
    }

    @Test
    fun differentPairsCannotUnwrapEachOthersBlobs() {
        val first = SecretKeyWrapper(freshPair())
        val second = SecretKeyWrapper(freshPair())
        val wrapped = first.wrap(SecretKeySpec(EncryptionHelper.generateRandom(32), "AES"))
        try {
            second.unwrap(wrapped)
            throw AssertionError("foreign wrapped key accepted")
        } catch (expected: Exception) {
            // expected
        }
    }
}
