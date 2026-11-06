package com.fairuniversal.offlineotp.Utilities

/**
 * Pure-JVM implementation of unkeyed BLAKE2b (RFC 7693) for digest lengths 1..64 bytes.
 *
 * Lives in `:core` so [Argon2id] needs no native JNI library or third-party crypto provider across
 * Android, desktop JVM, and the offline Kotlin sandbox.
 */
class Blake2b(private val outLen: Int = MAX_DIGEST_BYTES) {
    private val h = LongArray(8)
    private val buf = ByteArray(BLOCK_BYTES)
    private val m = LongArray(16)
    private val v = LongArray(16)
    private var bufPos = 0
    private var t0 = 0L
    private var t1 = 0L

    init {
        require(outLen in 1..MAX_DIGEST_BYTES) { "Invalid BLAKE2b digest length: $outLen" }
        reset()
    }

    fun reset() {
        System.arraycopy(IV, 0, h, 0, 8)
        // Parameter block P[0]: fanout = 1, depth = 1, keyLen = 0, digestLen = outLen.
        h[0] = h[0] xor (0x01010000L or outLen.toLong())
        buf.fill(0)
        m.fill(0L)
        v.fill(0L)
        bufPos = 0
        t0 = 0L
        t1 = 0L
    }

    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): Blake2b {
        require(offset >= 0 && length >= 0 && offset + length <= input.size) {
            "Invalid BLAKE2b input slice"
        }
        if (length == 0) return this

        var pos = offset
        var remaining = length

        if (bufPos == BLOCK_BYTES) {
            advanceCounter(BLOCK_BYTES)
            compress(buf, 0, lastBlock = false)
            bufPos = 0
        }

        if (bufPos > 0) {
            val fill = minOf(BLOCK_BYTES - bufPos, remaining)
            System.arraycopy(input, pos, buf, bufPos, fill)
            bufPos += fill
            pos += fill
            remaining -= fill
            if (bufPos == BLOCK_BYTES && remaining > 0) {
                advanceCounter(BLOCK_BYTES)
                compress(buf, 0, lastBlock = false)
                bufPos = 0
            }
        }

        while (remaining > BLOCK_BYTES) {
            advanceCounter(BLOCK_BYTES)
            compress(input, pos, lastBlock = false)
            pos += BLOCK_BYTES
            remaining -= BLOCK_BYTES
        }

        if (remaining > 0) {
            System.arraycopy(input, pos, buf, bufPos, remaining)
            bufPos += remaining
        }
        return this
    }

    /** Appends a 32-bit integer in little-endian byte order, as required by RFC 9106 inputs. */
    fun updateLeInt(value: Int): Blake2b {
        val le = ByteArray(4)
        le[0] = (value and 0xFF).toByte()
        le[1] = ((value ushr 8) and 0xFF).toByte()
        le[2] = ((value ushr 16) and 0xFF).toByte()
        le[3] = ((value ushr 24) and 0xFF).toByte()
        return update(le, 0, 4)
    }

    fun digest(): ByteArray {
        advanceCounter(bufPos)
        if (bufPos < BLOCK_BYTES) {
            buf.fill(0, bufPos, BLOCK_BYTES)
        }
        compress(buf, 0, lastBlock = true)

        val out = ByteArray(outLen)
        for (i in 0 until outLen) {
            out[i] = ((h[i ushr 3] ushr ((i and 7) shl 3)) and 0xFFL).toByte()
        }
        reset()
        return out
    }

    private fun advanceCounter(bytes: Int) {
        val inc = bytes.toLong() and 0xFFFFFFFFL
        val next = t0 + inc
        if (java.lang.Long.compareUnsigned(next, t0) < 0) {
            t1 += 1L
        }
        t0 = next
    }

    private fun compress(block: ByteArray, offset: Int, lastBlock: Boolean) {
        for (i in 0 until 16) {
            m[i] = loadLe64(block, offset + (i shl 3))
        }
        System.arraycopy(h, 0, v, 0, 8)
        System.arraycopy(IV, 0, v, 8, 8)
        v[12] = v[12] xor t0
        v[13] = v[13] xor t1
        if (lastBlock) {
            v[14] = v[14] xor -1L
        }

        for (round in 0 until 12) {
            val s = SIGMA[round]
            mix(0, 4, 8, 12, m[s[0]], m[s[1]])
            mix(1, 5, 9, 13, m[s[2]], m[s[3]])
            mix(2, 6, 10, 14, m[s[4]], m[s[5]])
            mix(3, 7, 11, 15, m[s[6]], m[s[7]])
            mix(0, 5, 10, 15, m[s[8]], m[s[9]])
            mix(1, 6, 11, 12, m[s[10]], m[s[11]])
            mix(2, 7, 8, 13, m[s[12]], m[s[13]])
            mix(3, 4, 9, 14, m[s[14]], m[s[15]])
        }

        for (i in 0 until 8) {
            h[i] = h[i] xor v[i] xor v[i + 8]
        }
    }

    private fun mix(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
        v[c] = v[c] + v[d]
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
        v[a] = v[a] + v[b] + y
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
    }

    companion object {
        const val BLOCK_BYTES = 128
        const val MAX_DIGEST_BYTES = 64

        private val IV = longArrayOf(
            0x6a09e667f3bcc908L,
            -0x4498517a7b3558c5L, // 0xbb67ae8584caa73b
            0x3c6ef372fe94f82bL,
            -0x5ab00ac5a0e2c90fL, // 0xa54ff53a5f1d36f1
            0x510e527fade682d1L,
            -0x64fa9773d4c193e1L, // 0x9b05688c2b3e6c1f
            0x1f83d9abfb41bd6bL,
            0x5be0cd19137e2179L
        )

        private val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3)
        )

        @JvmStatic
        @JvmOverloads
        fun hash(input: ByteArray, outLen: Int = MAX_DIGEST_BYTES): ByteArray =
            Blake2b(outLen).update(input, 0, input.size).digest()

        internal fun loadLe64(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24) or
                ((bytes[offset + 4].toLong() and 0xFFL) shl 32) or
                ((bytes[offset + 5].toLong() and 0xFFL) shl 40) or
                ((bytes[offset + 6].toLong() and 0xFFL) shl 48) or
                ((bytes[offset + 7].toLong() and 0xFFL) shl 56)

        internal fun storeLe64(value: Long, bytes: ByteArray, offset: Int) {
            bytes[offset] = (value and 0xFFL).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFFL).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFFL).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFFL).toByte()
            bytes[offset + 4] = ((value ushr 32) and 0xFFL).toByte()
            bytes[offset + 5] = ((value ushr 40) and 0xFFL).toByte()
            bytes[offset + 6] = ((value ushr 48) and 0xFFL).toByte()
            bytes[offset + 7] = ((value ushr 56) and 0xFFL).toByte()
        }
    }
}
