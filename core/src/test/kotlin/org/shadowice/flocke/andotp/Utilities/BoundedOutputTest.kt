/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for bounded provider output.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedOutputTest {
    @Test
    fun writesWithinTheLimitSucceed() {
        val output = BoundedOutput(10)
        output.write(0x41)
        output.write(ByteArray(9) { 0x42 })
        assertEquals(10, output.size())
        assertFalse(output.overflowed())
    }

    @Test
    fun overshootThrowsAndIsRemembered() {
        val output = BoundedOutput(4)
        try {
            output.write(ByteArray(5))
            throw AssertionError("oversized write accepted")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertTrue(output.overflowed())
    }

    @Test
    fun singleByteOvershootThrows() {
        val output = BoundedOutput(1)
        output.write(0x41)
        try {
            output.write(0x42)
            throw AssertionError("oversized single byte accepted")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertTrue(output.overflowed())
    }

    @Test
    fun negativeLimitIsRejected() {
        try {
            BoundedOutput(-1)
            throw AssertionError("negative limit accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
