/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the shared vault JSON array codec. These pin the exact document shape the Android
 * file store and the desktop client must both produce and accept, independent of any platform I/O.
 */
package org.shadowice.flocke.andotp.Database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

import java.util.ArrayList

class VaultJsonTest {

    private fun entry(issuer: String, label: String): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    @Test
    fun emptyVaultSerializesToAnEmptyArray() {
        assertEquals("[]", VaultJson.entriesToString(ArrayList()))
    }

    @Test
    fun nullAndOversizedListsAreRejectedBeforeEncoding() {
        try {
            VaultJson.entriesToString(null)
            throw AssertionError("null list accepted")
        } catch (expected: IllegalArgumentException) {
            // expected, and notably not wrapped: the Android facade relies on this exact type
        }

        val oversized = ArrayList<Entry>()
        repeat(InputPolicy.MAX_ENTRIES + 1) { oversized.add(entry("i$it", "l$it")) }
        try {
            VaultJson.entriesToString(oversized)
            throw AssertionError("oversized list accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun entriesSurviveAJsonRoundTripInOrder() {
        val source = arrayListOf(entry("GitHub", "github"), entry("Amazon", "aws"), entry("", "token"))
        val document = VaultJson.entriesToString(source)
        val restored = VaultJson.stringToEntries(document)

        assertEquals(source.size, restored.size)
        for (index in source.indices) {
            assertEquals(source[index].getIssuer(), restored[index].getIssuer())
            assertEquals(source[index].getLabel(), restored[index].getLabel())
            assertEquals(source[index].getType(), restored[index].getType())
        }
    }

    @Test
    fun parserAcceptsWhitespaceAroundAnEmptyArray() {
        assertTrue(VaultJson.stringToEntries(" [ ] ").isEmpty())
    }

    @Test
    fun parserRejectsNonArrayAndMalformedDocumentsWithTheCoreException() {
        for (document in listOf("{}", "\"not an array\"", "not json", "[{]", "")) {
            try {
                VaultJson.stringToEntries(document)
                throw AssertionError("malformed document accepted: $document")
            } catch (expected: VaultJson.MalformedVaultException) {
                // expected for every malformed shape
            }
        }
    }

    @Test
    fun parserRejectsOversizedDocumentsAndDeepNesting() {
        val oversized = "\"" + "x".repeat(BoundedInputReader.MAX_DOCUMENT_BYTES + 1) + "\""
        try {
            VaultJson.stringToEntries(oversized)
            throw AssertionError("oversized document accepted")
        } catch (expected: VaultJson.MalformedVaultException) {
            // size guard fires inside the parser's try block
        }

        val deep = "[".repeat(InputPolicy.MAX_JSON_DEPTH + 5) + "]".repeat(InputPolicy.MAX_JSON_DEPTH + 5)
        try {
            VaultJson.stringToEntries(deep)
            throw AssertionError("over-nested document accepted")
        } catch (expected: VaultJson.MalformedVaultException) {
            // jsonDepth guard
        }
    }

    @Test
    fun aValidFirstEntryFollowedByGarbageStillFailsAndDoesNotReturnAPrefix() {
        val document = VaultJson.entriesToString(arrayListOf(entry("Issuer", "label")))
        val truncated = document.substring(0, document.length - 2)
        try {
            VaultJson.stringToEntries(truncated)
            throw AssertionError("truncated document accepted")
        } catch (expected: VaultJson.MalformedVaultException) {
            // all-or-nothing: the parsed first entry is destroyed, nothing is returned
        }
    }
}
