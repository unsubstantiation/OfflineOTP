/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the portable OpenPGP backup codec. The PGPainless adapter itself needs the
 * real library (compile-verified in the sandbox), so the shared policy — recipient requirements,
 * document bounds, blank-output rejection and UTF-8 round trips — is pinned here with an in-memory
 * fake port that every platform adapter satisfies.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException

class OpenPgpBackupCodecTest {

    private val json = "[{\"secret\":\"JBSWY3DPEHPK3PXP\",\"label\":\"alice\"}]"

    /** Reversing stand-in: deterministic, content-preserving enough to verify orchestration. */
    private class ReversingPort : OpenPgpPort {
        override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>): ByteArray =
            plaintext.reversedArray()

        override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?): ByteArray =
            message.reversedArray()
    }

    @Test
    fun sealAndOpenRoundTripTheJsonDocument() {
        val port = ReversingPort()
        val message = OpenPgpBackupCodec.seal(port, json, listOf("cert-block".toByteArray()))
        val restored = OpenPgpBackupCodec.open(
            port, message, "secret-block".toByteArray(), null)
        assertEquals(json, restored)
    }

    @Test
    fun sealRequiresAtLeastOneNonEmptyRecipient() {
        val port = ReversingPort()
        for (recipients in listOf(emptyList(), listOf(ByteArray(0)))) {
            try {
                OpenPgpBackupCodec.seal(port, json, recipients)
                throw AssertionError("missing recipient accepted")
            } catch (expected: IllegalArgumentException) {
                // required
            }
        }
    }

    @Test
    fun openRejectsEmptyMessagesKeysAndBlankOutput() {
        val port = ReversingPort()
        try {
            OpenPgpBackupCodec.open(port, ByteArray(0), "k".toByteArray(), null)
            throw AssertionError("empty message accepted")
        } catch (expected: IllegalArgumentException) {
            // required
        }

        try {
            OpenPgpBackupCodec.open(port, "m".toByteArray(), ByteArray(0), null)
            throw AssertionError("empty secret key accepted")
        } catch (expected: IllegalArgumentException) {
            // required
        }

        val blankPort = object : OpenPgpPort {
            override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>) = ByteArray(1)
            override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?) =
                "   ".toByteArray(StandardCharsets.UTF_8)
        }
        try {
            OpenPgpBackupCodec.open(blankPort, "m".toByteArray(), "k".toByteArray(), null)
            throw AssertionError("blank provider output accepted")
        } catch (expected: IllegalArgumentException) {
            // OpenPgpOutputPolicy rule
        }
    }

    @Test
    fun oversizedDocumentsAreRejectedBeforeTheProviderRuns() {
        val port = ReversingPort()
        val huge = "x".repeat(BoundedInputReader.MAX_DOCUMENT_BYTES)
        try {
            OpenPgpBackupCodec.seal(port, huge, listOf("c".toByteArray()))
            throw AssertionError("oversized document accepted")
        } catch (expected: IllegalArgumentException) {
            // required
        }

        try {
            OpenPgpBackupCodec.open(
                port, ByteArray(BoundedInputReader.MAX_DOCUMENT_BYTES + 1), "k".toByteArray(), null)
            throw AssertionError("oversized message accepted")
        } catch (expected: IllegalArgumentException) {
            // required
        }
    }

    @Test
    fun oversizedProviderOutputCannotProduceAnUnrestorableBackup() {
        val port = object : OpenPgpPort {
            override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>) =
                ByteArray(BoundedInputReader.MAX_DOCUMENT_BYTES + 1)
            override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?) =
                ByteArray(0)
        }
        try {
            OpenPgpBackupCodec.seal(port, json, listOf(byteArrayOf(1)))
            throw AssertionError("oversized OpenPGP output accepted")
        } catch (expected: IllegalArgumentException) {
            // A produced backup must fit the same input limit as restore.
        }
    }

    @Test
    fun providerFailuresPropagateWithTheirDocumentedTypes() {
        val failing = object : OpenPgpPort {
            override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>): ByteArray =
                throw GeneralSecurityException("bad certificate")
            override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?): ByteArray =
                throw IOException("provider unavailable")
        }
        try {
            OpenPgpBackupCodec.seal(failing, json, listOf("c".toByteArray()))
            throw AssertionError("security failure swallowed")
        } catch (expected: GeneralSecurityException) {
            // required
        }
        try {
            OpenPgpBackupCodec.open(failing, "m".toByteArray(), "k".toByteArray(), null)
            throw AssertionError("I/O failure swallowed")
        } catch (expected: IOException) {
            // required
        }
    }

    @Test
    fun availabilityPolicyStillGatesEncryptionButNotRestore() {
        val nothing = OpenPgpAvailability.evaluate(false, true, 2)
        assertTrue(!nothing.canEncrypt && !nothing.canRestore)
        val readyNoRecipients = OpenPgpAvailability.evaluate(true, true, 0)
        assertTrue(!readyNoRecipients.canEncrypt && readyNoRecipients.canRestore)
        val ready = OpenPgpAvailability.evaluate(true, true, 1)
        assertTrue(ready.canEncrypt && ready.canRestore)
    }
}
