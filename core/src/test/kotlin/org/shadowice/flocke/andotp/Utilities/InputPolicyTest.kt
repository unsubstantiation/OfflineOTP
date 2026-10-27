/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the shared input bounds, including the JSON depth scanner's handling of
 * escapes, quotes and nesting.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputPolicyTest {
    @Test
    fun textBoundAcceptsNullAndShortValues() {
        InputPolicy.text(null, 4)
        InputPolicy.text("abc", 4)
    }

    @Test
    fun textBoundRejectsLongerValues() {
        try {
            InputPolicy.text("abcde", 4)
            throw AssertionError("oversized text was accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun jsonDepthAcceptsWellFormedDocuments() {
        InputPolicy.jsonDepth("""[{"a":[1,2,{"b":[]}]}]""")
        InputPolicy.jsonDepth("""["quotes with \" and \\ inside"]""")
        InputPolicy.jsonDepth("[]")
        InputPolicy.jsonDepth("""{"a":"} not a bracket"}""")
    }

    @Test
    fun jsonDepthRejectsDeepNestingAndAmbiguities() {
        val deep = "[".repeat(InputPolicy.MAX_JSON_DEPTH + 1)
        try {
            InputPolicy.jsonDepth(deep)
            throw AssertionError("deep nesting was accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        for (invalid in listOf(
            "[{]", "}", "\"unterminated", "[']'", "[/]", "[#]"
        )) {
            try {
                InputPolicy.jsonDepth(invalid)
                throw AssertionError("invalid JSON accepted: $invalid")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun imageBounds() {
        assertTrue(InputPolicy.validImage(1, 1))
        assertTrue(InputPolicy.validImage(InputPolicy.MAX_IMAGE_DIMENSION, 1))
        assertFalse(InputPolicy.validImage(0, 1))
        assertFalse(InputPolicy.validImage(-1, 1))
        assertFalse(InputPolicy.validImage(InputPolicy.MAX_IMAGE_DIMENSION + 1, 1))
        assertFalse(InputPolicy.validImage(4000, 1001))  // 4_004_000 > 4_000_000 pixels
    }

    @Test
    fun tokenParameterBounds() {
        InputPolicy.tokenParameters(1, 1, 0)
        InputPolicy.tokenParameters(InputPolicy.MAX_PERIOD_SECONDS, 9, Long.MAX_VALUE - 1)
        for (invalid in listOf(
            intArrayOf(0, 6), intArrayOf(InputPolicy.MAX_PERIOD_SECONDS + 1, 6),
            intArrayOf(30, 0), intArrayOf(30, 10)
        )) {
            try {
                InputPolicy.tokenParameters(invalid[0], invalid[1], 0)
                throw AssertionError("invalid token parameters accepted: ${invalid.toList()}")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
        try {
            InputPolicy.tokenParameters(30, 6, -1)
            throw AssertionError("negative counter accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            InputPolicy.tokenParameters(30, 6, Long.MAX_VALUE)
            throw AssertionError("Long.MAX_VALUE counter accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun constantsMatchTheDocumentedBounds() {
        assertEquals(16384, InputPolicy.MAX_URI_CHARS)
        assertEquals(2000, InputPolicy.MAX_ENTRIES)
        assertEquals(32, InputPolicy.MAX_JSON_DEPTH)
        assertEquals(2048, InputPolicy.MAX_SECRET_BYTES)
        assertEquals(86400, InputPolicy.MAX_PERIOD_SECONDS)
    }
}
