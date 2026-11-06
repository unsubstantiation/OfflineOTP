package org.shadowice.flocke.andotp.Utilities

import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool

/**
 * Pure-JVM implementation of Argon2id v1.3 (RFC 9106, `v = 0x13`, `y = 2`).
 *
 * Uses a single contiguous `LongArray` for the memory matrix and wipes all intermediate buffers
 * before returning. Lanes within each synchronization slice are independent by RFC 9106 construction
 * and execute concurrently on multi-lane configurations while producing deterministic output.
 */
object Argon2id {
    const val VERSION = 0x13
    const val TYPE_ARGON2ID = 2
    const val BLOCK_BYTES = 1024
    const val BLOCK_QWORDS = 128
    const val SYNC_POINTS = 4

    const val MIN_SALT_BYTES = 8
    const val MIN_TAG_BYTES = 4
    const val MAX_TAG_BYTES = 1024
    const val MAX_PARALLELISM = 16
    const val MAX_MEMORY_KIB = 262_144 // 256 MiB ceiling for untrusted headers/settings
    const val MAX_ITERATIONS = 64
    const val MAX_BLOCK_PASSES = 1_048_576L

    private const val MASK_32 = 0xFFFFFFFFL

    /**
     * Derives [tagLength] bytes from [password] (UTF-8 encoded) and [salt] using Argon2id v1.3.
     */
    @JvmStatic
    @JvmOverloads
    fun deriveFromPassword(
        password: String?,
        salt: ByteArray?,
        memoryKiB: Int = Constants.ARGON2_MEMORY_KIB,
        iterations: Int = Constants.ARGON2_ITERATIONS,
        parallelism: Int = Constants.ARGON2_PARALLELISM,
        tagLength: Int = Constants.ARGON2_HASH_LENGTH
    ): ByteArray {
        if (password == null || password.length > InputPolicy.MAX_TEXT_CHARS) {
            throw IllegalArgumentException("Invalid credential parameters")
        }
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        return try {
            derive(
                password = passwordBytes,
                salt = salt,
                memoryKiB = memoryKiB,
                iterations = iterations,
                parallelism = parallelism,
                tagLength = tagLength
            )
        } finally {
            passwordBytes.fill(0)
        }
    }

    /**
     * Raw RFC 9106 Argon2id v1.3 derivation. Optional [secret] and [associatedData] default to
     * empty so both unkeyed derivations and the full RFC 9106 Section 5.3 test vector use the exact
     * same code path.
     */
    @JvmStatic
    @JvmOverloads
    fun derive(
        password: ByteArray?,
        salt: ByteArray?,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        tagLength: Int,
        secret: ByteArray = ByteArray(0),
        associatedData: ByteArray = ByteArray(0)
    ): ByteArray {
        validateParameters(password, salt, memoryKiB, iterations, parallelism, tagLength)
        val pw = password!!
        val saltBytes = salt!!

        val mPrime = 4 * parallelism * (memoryKiB / (4 * parallelism))
        val laneLength = mPrime / parallelism
        val segmentLength = laneLength / SYNC_POINTS

        val h0 = initialHash(
            pw = pw,
            salt = saltBytes,
            secret = secret,
            associatedData = associatedData,
            parallelism = parallelism,
            tagLength = tagLength,
            memoryKiB = memoryKiB,
            iterations = iterations
        )
        val memory = LongArray(mPrime * BLOCK_QWORDS)
        val cBlock = LongArray(BLOCK_QWORDS)
        val cBytes = ByteArray(BLOCK_BYTES)
        try {
            initializeFirstBlocks(h0, parallelism, laneLength, memory)

            val runParallel = parallelism > 1 && mPrime >= 256
            for (pass in 0 until iterations) {
                for (slice in 0 until SYNC_POINTS) {
                    if (runParallel) {
                        val tasks = ArrayList<Callable<Unit>>(parallelism)
                        for (lane in 0 until parallelism) {
                            tasks.add(Callable {
                                fillSegment(
                                    memory = memory,
                                    pass = pass,
                                    lane = lane,
                                    slice = slice,
                                    parallelism = parallelism,
                                    mPrime = mPrime,
                                    iterations = iterations,
                                    laneLength = laneLength,
                                    segmentLength = segmentLength
                                )
                            })
                        }
                        val futures = ForkJoinPool.commonPool().invokeAll(tasks)
                        for (future in futures) {
                            try {
                                future.get()
                            } catch (e: ExecutionException) {
                                val cause = e.cause
                                if (cause is RuntimeException) throw cause
                                if (cause is Error) throw cause
                                throw IllegalStateException("Argon2id lane execution failed", cause)
                            }
                        }
                    } else {
                        for (lane in 0 until parallelism) {
                            fillSegment(
                                memory = memory,
                                pass = pass,
                                lane = lane,
                                slice = slice,
                                parallelism = parallelism,
                                mPrime = mPrime,
                                iterations = iterations,
                                laneLength = laneLength,
                                segmentLength = segmentLength
                            )
                        }
                    }
                }
            }

            // Final block C = XOR of the last column in every lane.
            val firstLastOffset = (laneLength - 1) * BLOCK_QWORDS
            System.arraycopy(memory, firstLastOffset, cBlock, 0, BLOCK_QWORDS)
            for (lane in 1 until parallelism) {
                val lastOffset = (lane * laneLength + (laneLength - 1)) * BLOCK_QWORDS
                for (k in 0 until BLOCK_QWORDS) {
                    cBlock[k] = cBlock[k] xor memory[lastOffset + k]
                }
            }
            for (k in 0 until BLOCK_QWORDS) {
                Blake2b.storeLe64(cBlock[k], cBytes, k shl 3)
            }
            return variableLengthHash(tagLength, cBytes)
        } finally {
            h0.fill(0)
            cBlock.fill(0L)
            cBytes.fill(0)
            memory.fill(0L)
        }
    }

    @JvmStatic
    fun validateParameters(
        password: ByteArray?,
        salt: ByteArray?,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        tagLength: Int
    ) {
        if (password == null ||
            salt == null || salt.size < MIN_SALT_BYTES || salt.size > 1024 ||
            parallelism < 1 || parallelism > MAX_PARALLELISM ||
            memoryKiB < 8 * parallelism || memoryKiB > MAX_MEMORY_KIB ||
            iterations < 1 || iterations > MAX_ITERATIONS ||
            memoryKiB.toLong() * iterations.toLong() > MAX_BLOCK_PASSES ||
            tagLength < MIN_TAG_BYTES || tagLength > MAX_TAG_BYTES
        ) {
            throw IllegalArgumentException("Invalid Argon2id parameters")
        }
    }

    private fun initialHash(
        pw: ByteArray,
        salt: ByteArray,
        secret: ByteArray,
        associatedData: ByteArray,
        parallelism: Int,
        tagLength: Int,
        memoryKiB: Int,
        iterations: Int
    ): ByteArray {
        val blake = Blake2b(64)
        blake.updateLeInt(parallelism)
        blake.updateLeInt(tagLength)
        blake.updateLeInt(memoryKiB)
        blake.updateLeInt(iterations)
        blake.updateLeInt(VERSION)
        blake.updateLeInt(TYPE_ARGON2ID)
        blake.updateLeInt(pw.size)
        blake.update(pw)
        blake.updateLeInt(salt.size)
        blake.update(salt)
        blake.updateLeInt(secret.size)
        blake.update(secret)
        blake.updateLeInt(associatedData.size)
        blake.update(associatedData)
        return blake.digest()
    }

    private fun initializeFirstBlocks(
        h0: ByteArray,
        parallelism: Int,
        laneLength: Int,
        memory: LongArray
    ) {
        val seed = ByteArray(72)
        System.arraycopy(h0, 0, seed, 0, 64)
        try {
            for (lane in 0 until parallelism) {
                writeLe32(lane, seed, 68)
                for (blockIndex in 0..1) {
                    writeLe32(blockIndex, seed, 64)
                    val blockBytes = variableLengthHash(BLOCK_BYTES, seed)
                    try {
                        val destOffset = (lane * laneLength + blockIndex) * BLOCK_QWORDS
                        for (k in 0 until BLOCK_QWORDS) {
                            memory[destOffset + k] = Blake2b.loadLe64(blockBytes, k shl 3)
                        }
                    } finally {
                        blockBytes.fill(0)
                    }
                }
            }
        } finally {
            seed.fill(0)
        }
    }

    private fun fillSegment(
        memory: LongArray,
        pass: Int,
        lane: Int,
        slice: Int,
        parallelism: Int,
        mPrime: Int,
        iterations: Int,
        laneLength: Int,
        segmentLength: Int
    ) {
        val dataIndependent = (pass == 0 && slice < 2)
        val rBuf = LongArray(BLOCK_QWORDS)
        val zBuf = LongArray(BLOCK_QWORDS)
        val zeroBlock = if (dataIndependent) LongArray(BLOCK_QWORDS) else null
        val zInBlock = if (dataIndependent) LongArray(BLOCK_QWORDS) else null
        val addrBlock = if (dataIndependent) LongArray(BLOCK_QWORDS) else null
        val tmpBlock = if (dataIndependent) LongArray(BLOCK_QWORDS) else null

        try {
            var counter = 0L
            val startJ = if (pass == 0 && slice == 0) 2 else 0
            if (dataIndependent) {
                counter = 1L
                nextAddresses(
                    zero = zeroBlock!!,
                    zIn = zInBlock!!,
                    tmp = tmpBlock!!,
                    addrOut = addrBlock!!,
                    rBuf = rBuf,
                    zBuf = zBuf,
                    pass = pass,
                    lane = lane,
                    slice = slice,
                    mPrime = mPrime,
                    iterations = iterations,
                    counter = counter
                )
            }

            val laneBase = lane * laneLength
            for (j in startJ until segmentLength) {
                if (dataIndependent && (j and 127) == 0 && j != 0) {
                    counter += 1L
                    nextAddresses(
                        zero = zeroBlock!!,
                        zIn = zInBlock!!,
                        tmp = tmpBlock!!,
                        addrOut = addrBlock!!,
                        rBuf = rBuf,
                        zBuf = zBuf,
                        pass = pass,
                        lane = lane,
                        slice = slice,
                        mPrime = mPrime,
                        iterations = iterations,
                        counter = counter
                    )
                }

                val col = slice * segmentLength + j
                val prevCol = if (col == 0) laneLength - 1 else col - 1
                val prevOffset = (laneBase + prevCol) * BLOCK_QWORDS

                val pseudoRand = if (dataIndependent) {
                    addrBlock!![j and 127]
                } else {
                    memory[prevOffset]
                }

                val j1 = pseudoRand and MASK_32
                val j2 = (pseudoRand ushr 32) and MASK_32
                val refLane = if (pass == 0 && slice == 0) lane else (j2 % parallelism.toLong()).toInt()

                val startPos: Int
                val wSize: Int
                if (pass == 0) {
                    startPos = 0
                    wSize = if (slice == 0 || refLane == lane) {
                        slice * segmentLength + j - 1
                    } else {
                        slice * segmentLength - if (j == 0) 1 else 0
                    }
                } else {
                    startPos = if (slice == SYNC_POINTS - 1) 0 else (slice + 1) * segmentLength
                    wSize = if (refLane == lane) {
                        laneLength - segmentLength + j - 1
                    } else {
                        laneLength - segmentLength - if (j == 0) 1 else 0
                    }
                }

                val x = (j1 * j1) ushr 32
                val y = (wSize.toLong() * x) ushr 32
                val zz = wSize - 1 - y.toInt()
                val refCol = (startPos + zz) % laneLength

                val refOffset = (refLane * laneLength + refCol) * BLOCK_QWORDS
                val currOffset = (laneBase + col) * BLOCK_QWORDS

                compressBlock(
                    xArr = memory,
                    xOffset = prevOffset,
                    yArr = memory,
                    yOffset = refOffset,
                    destArr = memory,
                    destOffset = currOffset,
                    xorWithDest = (pass != 0),
                    rBuf = rBuf,
                    zBuf = zBuf
                )
            }
        } finally {
            rBuf.fill(0L)
            zBuf.fill(0L)
            addrBlock?.fill(0L)
            tmpBlock?.fill(0L)
            zInBlock?.fill(0L)
        }
    }

    private fun nextAddresses(
        zero: LongArray,
        zIn: LongArray,
        tmp: LongArray,
        addrOut: LongArray,
        rBuf: LongArray,
        zBuf: LongArray,
        pass: Int,
        lane: Int,
        slice: Int,
        mPrime: Int,
        iterations: Int,
        counter: Long
    ) {
        zIn.fill(0L)
        zIn[0] = pass.toLong()
        zIn[1] = lane.toLong()
        zIn[2] = slice.toLong()
        zIn[3] = mPrime.toLong()
        zIn[4] = iterations.toLong()
        zIn[5] = TYPE_ARGON2ID.toLong()
        zIn[6] = counter

        compressBlock(zero, 0, zIn, 0, tmp, 0, xorWithDest = false, rBuf = rBuf, zBuf = zBuf)
        compressBlock(zero, 0, tmp, 0, addrOut, 0, xorWithDest = false, rBuf = rBuf, zBuf = zBuf)
    }

    private fun compressBlock(
        xArr: LongArray,
        xOffset: Int,
        yArr: LongArray,
        yOffset: Int,
        destArr: LongArray,
        destOffset: Int,
        xorWithDest: Boolean,
        rBuf: LongArray,
        zBuf: LongArray
    ) {
        for (k in 0 until BLOCK_QWORDS) {
            val xored = xArr[xOffset + k] xor yArr[yOffset + k]
            rBuf[k] = xored
            zBuf[k] = xored
        }

        // Apply permutation P row-by-row (8 rows of 16 uint64 words).
        for (row in 0 until 8) {
            val b = row shl 4
            permute16(
                zBuf,
                b, b + 1, b + 2, b + 3,
                b + 4, b + 5, b + 6, b + 7,
                b + 8, b + 9, b + 10, b + 11,
                b + 12, b + 13, b + 14, b + 15
            )
        }

        // Apply permutation P column-by-column (8 columns of 16-byte = 2-uint64 registers).
        for (col in 0 until 8) {
            val c = col shl 1
            permute16(
                zBuf,
                c, c + 1, c + 16, c + 17,
                c + 32, c + 33, c + 48, c + 49,
                c + 64, c + 65, c + 80, c + 81,
                c + 96, c + 97, c + 112, c + 113
            )
        }

        if (xorWithDest) {
            for (k in 0 until BLOCK_QWORDS) {
                destArr[destOffset + k] = destArr[destOffset + k] xor zBuf[k] xor rBuf[k]
            }
        } else {
            for (k in 0 until BLOCK_QWORDS) {
                destArr[destOffset + k] = zBuf[k] xor rBuf[k]
            }
        }
    }

    private fun permute16(
        v: LongArray,
        i0: Int, i1: Int, i2: Int, i3: Int,
        i4: Int, i5: Int, i6: Int, i7: Int,
        i8: Int, i9: Int, i10: Int, i11: Int,
        i12: Int, i13: Int, i14: Int, i15: Int
    ) {
        gb(v, i0, i4, i8, i12)
        gb(v, i1, i5, i9, i13)
        gb(v, i2, i6, i10, i14)
        gb(v, i3, i7, i11, i15)
        gb(v, i0, i5, i10, i15)
        gb(v, i1, i6, i11, i12)
        gb(v, i2, i7, i8, i13)
        gb(v, i3, i4, i9, i14)
    }

    private fun gb(v: LongArray, a: Int, b: Int, c: Int, d: Int) {
        var va = v[a]
        var vb = v[b]
        var vc = v[c]
        var vd = v[d]

        va = va + vb + 2L * (va and MASK_32) * (vb and MASK_32)
        vd = java.lang.Long.rotateRight(vd xor va, 32)
        vc = vc + vd + 2L * (vc and MASK_32) * (vd and MASK_32)
        vb = java.lang.Long.rotateRight(vb xor vc, 24)

        va = va + vb + 2L * (va and MASK_32) * (vb and MASK_32)
        vd = java.lang.Long.rotateRight(vd xor va, 16)
        vc = vc + vd + 2L * (vc and MASK_32) * (vd and MASK_32)
        vb = java.lang.Long.rotateRight(vb xor vc, 63)

        v[a] = va
        v[b] = vb
        v[c] = vc
        v[d] = vd
    }

    /** RFC 9106 Section 3.2 variable-length hash `H'^T(X)`. */
    internal fun variableLengthHash(outLen: Int, input: ByteArray): ByteArray {
        if (outLen <= 64) {
            return Blake2b(outLen)
                .updateLeInt(outLen)
                .update(input)
                .digest()
        }
        val r = (outLen + 31) / 32 - 2
        val out = ByteArray(outLen)
        var v = Blake2b(64)
            .updateLeInt(outLen)
            .update(input)
            .digest()
        try {
            System.arraycopy(v, 0, out, 0, 32)
            for (i in 2..r) {
                val next = Blake2b.hash(v, 64)
                v.fill(0)
                v = next
                System.arraycopy(v, 0, out, (i - 1) * 32, 32)
            }
            val remaining = outLen - 32 * r
            val tail = Blake2b.hash(v, remaining)
            try {
                System.arraycopy(tail, 0, out, 32 * r, remaining)
            } finally {
                tail.fill(0)
            }
            return out
        } finally {
            v.fill(0)
        }
    }

    private fun writeLe32(value: Int, dest: ByteArray, offset: Int) {
        dest[offset] = (value and 0xFF).toByte()
        dest[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dest[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        dest[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
