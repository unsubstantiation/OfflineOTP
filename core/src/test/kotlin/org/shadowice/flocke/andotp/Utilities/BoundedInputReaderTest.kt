/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for bounded document reads: EOF, exact limit, overshoot detection and the
 * zero-returning provider stream.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class BoundedInputReaderTest {
    @Test
    fun readsUntilEof() {
        val data = ByteArray(100) { it.toByte() }
        assertArrayEquals(data, BoundedInputReader.read(ByteArrayInputStream(data), 1000))
    }

    @Test
    fun emptyStreamYieldsEmptyResult() {
        assertArrayEquals(ByteArray(0), BoundedInputReader.read(ByteArrayInputStream(ByteArray(0)), 10))
    }

    @Test
    fun readsExactlyTheLimit() {
        val data = ByteArray(50) { it.toByte() }
        assertArrayEquals(data, BoundedInputReader.read(ByteArrayInputStream(data), 50))
    }

    @Test
    fun overshootIsRejected() {
        val data = ByteArray(51) { it.toByte() }
        try {
            BoundedInputReader.read(ByteArrayInputStream(data), 50)
            throw AssertionError("oversized document was accepted")
        } catch (expected: IOException) {
            // expected
        }
    }

    @Test
    fun zeroReturningStreamsStillMakeProgress() {
        val data = ByteArray(10) { it.toByte() }
        val stream = ZeroThenDataStream(data)
        assertArrayEquals(data, BoundedInputReader.read(stream, 50))
    }

    @Test
    fun zeroReturningStreamAtEofTerminates() {
        val stream = ZeroThenDataStream(ByteArray(0))
        assertArrayEquals(ByteArray(0), BoundedInputReader.read(stream, 50))
    }

    @Test
    fun invalidInputsAreRejected() {
        try {
            BoundedInputReader.read(null, 10)
            throw AssertionError("null stream accepted")
        } catch (expected: IOException) {
            // expected
        }
        for (limit in intArrayOf(-1, Int.MAX_VALUE)) {
            try {
                BoundedInputReader.read(ByteArrayInputStream(ByteArray(1)), limit)
                throw AssertionError("invalid limit accepted: $limit")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    /** Returns a zero-length read first (as some providers do), then the data, then EOF. */
    private class ZeroThenDataStream(private val data: ByteArray) : InputStream() {
        private var servedZero = false
        private var position = 0

        override fun read(): Int {
            if (position < data.size) return data[position++].toInt() and 0xff
            return -1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!servedZero) {
                servedZero = true
                return 0
            }
            if (position >= data.size) return -1
            var count = 0
            while (count < length && position < data.size) {
                buffer[offset + count] = data[position++]
                count++
            }
            return count
        }
    }
}
