import Dispatch
import Foundation

/// Pure-Swift RFC 7693 unkeyed BLAKE2b (1...64 byte digests) used by `Argon2id`.
///
/// Implemented without external SwiftPM dependencies so `OfflineOTPKit` builds offline on macOS and
/// iOS while producing bit-identical digests to `:core/Utilities/Blake2b.kt`.
public final class Blake2b {
    public static let blockBytes = 128
    public static let maxDigestBytes = 64

    private static let iv: [UInt64] = [
        0x6a09e667f3bcc908, 0xbb67ae8584caa73b,
        0x3c6ef372fe94f82b, 0xa54ff53a5f1d36f1,
        0x510e527fade682d1, 0x9b05688c2b3e6c1f,
        0x1f83d9abfb41bd6b, 0x5be0cd19137e2179,
    ]

    private static let sigma: [[Int]] = [
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
        [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
        [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4],
        [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
        [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13],
        [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
        [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11],
        [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
        [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5],
        [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0],
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
        [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
    ]

    private let outLen: Int
    private var h = [UInt64](repeating: 0, count: 8)
    private var buf = [UInt8](repeating: 0, count: 128)
    private var m = [UInt64](repeating: 0, count: 16)
    private var v = [UInt64](repeating: 0, count: 16)
    private var bufPos = 0
    private var t0: UInt64 = 0
    private var t1: UInt64 = 0

    public init(outLen: Int = Blake2b.maxDigestBytes) throws {
        guard outLen >= 1, outLen <= Blake2b.maxDigestBytes else {
            throw VaultCryptoError.invalidParameters
        }
        self.outLen = outLen
        reset()
    }

    public func reset() {
        for i in 0..<8 {
            h[i] = Blake2b.iv[i]
        }
        h[0] ^= 0x01010000 | UInt64(outLen)
        for i in 0..<128 { buf[i] = 0 }
        for i in 0..<16 { m[i] = 0; v[i] = 0 }
        bufPos = 0
        t0 = 0
        t1 = 0
    }

    @discardableResult
    public func update(_ bytes: [UInt8]) -> Blake2b {
        if bytes.isEmpty { return self }
        var pos = 0
        var remaining = bytes.count

        if bufPos == Blake2b.blockBytes {
            advanceCounter(Blake2b.blockBytes)
            compress(buf, offset: 0, lastBlock: false)
            bufPos = 0
        }

        if bufPos > 0 {
            let fill = min(Blake2b.blockBytes - bufPos, remaining)
            for k in 0..<fill {
                buf[bufPos + k] = bytes[pos + k]
            }
            bufPos += fill
            pos += fill
            remaining -= fill
            if bufPos == Blake2b.blockBytes && remaining > 0 {
                advanceCounter(Blake2b.blockBytes)
                compress(buf, offset: 0, lastBlock: false)
                bufPos = 0
            }
        }

        while remaining > Blake2b.blockBytes {
            advanceCounter(Blake2b.blockBytes)
            compress(bytes, offset: pos, lastBlock: false)
            pos += Blake2b.blockBytes
            remaining -= Blake2b.blockBytes
        }

        if remaining > 0 {
            for k in 0..<remaining {
                buf[bufPos + k] = bytes[pos + k]
            }
            bufPos += remaining
        }
        return self
    }

    @discardableResult
    public func updateLeUInt32(_ value: UInt32) -> Blake2b {
        update([
            UInt8(truncatingIfNeeded: value),
            UInt8(truncatingIfNeeded: value >> 8),
            UInt8(truncatingIfNeeded: value >> 16),
            UInt8(truncatingIfNeeded: value >> 24),
        ])
    }

    public func digest() -> [UInt8] {
        advanceCounter(bufPos)
        if bufPos < Blake2b.blockBytes {
            for k in bufPos..<Blake2b.blockBytes {
                buf[k] = 0
            }
        }
        compress(buf, offset: 0, lastBlock: true)

        var out = [UInt8](repeating: 0, count: outLen)
        for i in 0..<outLen {
            out[i] = UInt8(truncatingIfNeeded: h[i >> 3] >> ((i & 7) << 3))
        }
        reset()
        return out
    }

    public static func hash(_ input: [UInt8], outLen: Int = Blake2b.maxDigestBytes) throws -> [UInt8] {
        try Blake2b(outLen: outLen).update(input).digest()
    }

    private func advanceCounter(_ bytes: Int) {
        let (next, overflow) = t0.addingReportingOverflow(UInt64(bytes))
        t0 = next
        if overflow {
            t1 &+= 1
        }
    }

    private func compress(_ block: [UInt8], offset: Int, lastBlock: Bool) {
        for i in 0..<16 {
            m[i] = Blake2b.loadLe64(block, offset: offset + (i << 3))
        }
        for i in 0..<8 {
            v[i] = h[i]
            v[i + 8] = Blake2b.iv[i]
        }
        v[12] ^= t0
        v[13] ^= t1
        if lastBlock {
            v[14] ^= ~UInt64(0)
        }

        for round in 0..<12 {
            let s = Blake2b.sigma[round]
            mix(0, 4, 8, 12, m[s[0]], m[s[1]])
            mix(1, 5, 9, 13, m[s[2]], m[s[3]])
            mix(2, 6, 10, 14, m[s[4]], m[s[5]])
            mix(3, 7, 11, 15, m[s[6]], m[s[7]])
            mix(0, 5, 10, 15, m[s[8]], m[s[9]])
            mix(1, 6, 11, 12, m[s[10]], m[s[11]])
            mix(2, 7, 8, 13, m[s[12]], m[s[13]])
            mix(3, 4, 9, 14, m[s[14]], m[s[15]])
        }

        for i in 0..<8 {
            h[i] ^= v[i] ^ v[i + 8]
        }
    }

    @inline(__always)
    private func mix(_ a: Int, _ b: Int, _ c: Int, _ d: Int, _ x: UInt64, _ y: UInt64) {
        v[a] = v[a] &+ v[b] &+ x
        v[d] = Blake2b.rotr64(v[d] ^ v[a], 32)
        v[c] = v[c] &+ v[d]
        v[b] = Blake2b.rotr64(v[b] ^ v[c], 24)
        v[a] = v[a] &+ v[b] &+ y
        v[d] = Blake2b.rotr64(v[d] ^ v[a], 16)
        v[c] = v[c] &+ v[d]
        v[b] = Blake2b.rotr64(v[b] ^ v[c], 63)
    }

    @inline(__always)
    internal static func rotr64(_ x: UInt64, _ n: UInt64) -> UInt64 {
        (x >> n) | (x << (64 - n))
    }

    /// Reads eight bytes as a little-endian word.
    ///
    /// Each byte is widened in its own statement rather than folded into one eight-term `|` chain:
    /// a single expression of that shape makes the Swift type checker exhaust itself (it has to
    /// consider every integer-literal type for every operand) and the compiler reports
    /// "unable to type-check this expression in reasonable time" instead of building the module.
    @inline(__always)
    internal static func loadLe64(_ bytes: [UInt8], offset: Int) -> UInt64 {
        var value: UInt64 = 0
        for index in 0..<8 {
            value |= UInt64(bytes[offset + index]) << UInt64(index * 8)
        }
        return value
    }

    @inline(__always)
    internal static func storeLe64(_ value: UInt64, into bytes: inout [UInt8], offset: Int) {
        bytes[offset] = UInt8(truncatingIfNeeded: value)
        bytes[offset + 1] = UInt8(truncatingIfNeeded: value >> 8)
        bytes[offset + 2] = UInt8(truncatingIfNeeded: value >> 16)
        bytes[offset + 3] = UInt8(truncatingIfNeeded: value >> 24)
        bytes[offset + 4] = UInt8(truncatingIfNeeded: value >> 32)
        bytes[offset + 5] = UInt8(truncatingIfNeeded: value >> 40)
        bytes[offset + 6] = UInt8(truncatingIfNeeded: value >> 48)
        bytes[offset + 7] = UInt8(truncatingIfNeeded: value >> 56)
    }
}

/// Pure-Swift Argon2id v1.3 (RFC 9106, `v = 0x13`, `y = 2`), byte-identical to `:core/Utilities/Argon2id.kt`.
///
/// Operates directly on an `UnsafeMutablePointer<UInt64>` block buffer so unoptimized debug runs
/// (`swift test`) stay fast without array bounds-checking overhead, and wipes all allocated memory
/// before deallocation.
public enum Argon2id {
    public static let version: UInt32 = 0x13
    public static let typeArgon2id: UInt32 = 2
    public static let blockBytes = 1024
    public static let blockQwords = 128
    public static let syncPoints = 4

    public static let minSaltBytes = 8
    public static let minTagBytes = 4
    public static let maxTagBytes = 1024
    public static let maxParallelism = 16
    public static let maxMemoryKiB = 262_144
    public static let maxIterations = 64
    public static let maxBlockPasses: Int64 = 1_048_576

    public static func validateParameters(
        saltCount: Int,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        tagLength: Int
    ) throws {
        guard saltCount >= minSaltBytes, saltCount <= 1024,
              parallelism >= 1, parallelism <= maxParallelism,
              memoryKiB >= 8 * parallelism, memoryKiB <= maxMemoryKiB,
              iterations >= 1, iterations <= maxIterations,
              Int64(memoryKiB) * Int64(iterations) <= maxBlockPasses,
              tagLength >= minTagBytes, tagLength <= maxTagBytes else {
            throw VaultCryptoError.invalidParameters
        }
    }

    public static func derive(
        password: [UInt8],
        salt: [UInt8],
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        tagLength: Int,
        secret: [UInt8] = [],
        associatedData: [UInt8] = []
    ) throws -> [UInt8] {
        try validateParameters(
            saltCount: salt.count,
            memoryKiB: memoryKiB,
            iterations: iterations,
            parallelism: parallelism,
            tagLength: tagLength
        )

        let mPrime = 4 * parallelism * (memoryKiB / (4 * parallelism))
        let laneLength = mPrime / parallelism
        let segmentLength = laneLength / syncPoints
        let totalWords = mPrime * blockQwords

        var h0 = try initialHash(
            password: password,
            salt: salt,
            secret: secret,
            associatedData: associatedData,
            parallelism: parallelism,
            tagLength: tagLength,
            memoryKiB: memoryKiB,
            iterations: iterations
        )
        defer {
            for i in 0..<h0.count { h0[i] = 0 }
        }

        let memory = UnsafeMutablePointer<UInt64>.allocate(capacity: totalWords)
        memory.initialize(repeating: 0, count: totalWords)
        defer {
            memory.update(repeating: 0, count: totalWords)
            memory.deallocate()
        }

        try initializeFirstBlocks(
            h0: h0,
            parallelism: parallelism,
            laneLength: laneLength,
            memory: memory
        )

        let baseAddress = Int(bitPattern: memory)
        let runParallel = parallelism > 1 && mPrime >= 256
        for pass in 0..<iterations {
            for slice in 0..<syncPoints {
                if runParallel {
                    DispatchQueue.concurrentPerform(iterations: parallelism) { lane in
                        guard let ptr = UnsafeMutablePointer<UInt64>(bitPattern: baseAddress) else {
                            return
                        }
                        fillSegment(
                            memory: ptr,
                            pass: pass,
                            lane: lane,
                            slice: slice,
                            parallelism: parallelism,
                            mPrime: mPrime,
                            iterations: iterations,
                            laneLength: laneLength,
                            segmentLength: segmentLength
                        )
                    }
                } else {
                    for lane in 0..<parallelism {
                        fillSegment(
                            memory: memory,
                            pass: pass,
                            lane: lane,
                            slice: slice,
                            parallelism: parallelism,
                            mPrime: mPrime,
                            iterations: iterations,
                            laneLength: laneLength,
                            segmentLength: segmentLength
                        )
                    }
                }
            }
        }

        var cBytes = [UInt8](repeating: 0, count: blockBytes)
        defer {
            for i in 0..<cBytes.count { cBytes[i] = 0 }
        }

        for k in 0..<blockQwords {
            var word = memory[(laneLength - 1) * blockQwords + k]
            if parallelism > 1 {
                for lane in 1..<parallelism {
                    word ^= memory[(lane * laneLength + (laneLength - 1)) * blockQwords + k]
                }
            }
            Blake2b.storeLe64(word, into: &cBytes, offset: k << 3)
        }

        return try variableLengthHash(outLen: tagLength, input: cBytes)
    }

    private static func initialHash(
        password: [UInt8],
        salt: [UInt8],
        secret: [UInt8],
        associatedData: [UInt8],
        parallelism: Int,
        tagLength: Int,
        memoryKiB: Int,
        iterations: Int
    ) throws -> [UInt8] {
        let blake = try Blake2b(outLen: 64)
        blake.updateLeUInt32(UInt32(parallelism))
        blake.updateLeUInt32(UInt32(tagLength))
        blake.updateLeUInt32(UInt32(memoryKiB))
        blake.updateLeUInt32(UInt32(iterations))
        blake.updateLeUInt32(version)
        blake.updateLeUInt32(typeArgon2id)
        blake.updateLeUInt32(UInt32(password.count))
        blake.update(password)
        blake.updateLeUInt32(UInt32(salt.count))
        blake.update(salt)
        blake.updateLeUInt32(UInt32(secret.count))
        blake.update(secret)
        blake.updateLeUInt32(UInt32(associatedData.count))
        blake.update(associatedData)
        return blake.digest()
    }

    private static func initializeFirstBlocks(
        h0: [UInt8],
        parallelism: Int,
        laneLength: Int,
        memory: UnsafeMutablePointer<UInt64>
    ) throws {
        var seed = [UInt8](repeating: 0, count: 72)
        for i in 0..<64 { seed[i] = h0[i] }
        defer {
            for i in 0..<seed.count { seed[i] = 0 }
        }

        for lane in 0..<parallelism {
            writeLe32(UInt32(lane), into: &seed, offset: 68)
            for blockIndex in 0...1 {
                writeLe32(UInt32(blockIndex), into: &seed, offset: 64)
                var blockBytes = try variableLengthHash(outLen: blockBytes, input: seed)
                defer {
                    for i in 0..<blockBytes.count { blockBytes[i] = 0 }
                }
                let destOffset = (lane * laneLength + blockIndex) * blockQwords
                for k in 0..<blockQwords {
                    memory[destOffset + k] = Blake2b.loadLe64(blockBytes, offset: k << 3)
                }
            }
        }
    }

    private static func fillSegment(
        memory: UnsafeMutablePointer<UInt64>,
        pass: Int,
        lane: Int,
        slice: Int,
        parallelism: Int,
        mPrime: Int,
        iterations: Int,
        laneLength: Int,
        segmentLength: Int
    ) {
        let dataIndependent = (pass == 0 && slice < 2)
        let scratchWords = dataIndependent ? (6 * blockQwords) : (2 * blockQwords)
        let scratch = UnsafeMutablePointer<UInt64>.allocate(capacity: scratchWords)
        scratch.initialize(repeating: 0, count: scratchWords)
        defer {
            scratch.update(repeating: 0, count: scratchWords)
            scratch.deallocate()
        }

        let rBuf = scratch
        let zBuf = scratch.advanced(by: blockQwords)
        let zeroBlock = scratch.advanced(by: 2 * blockQwords)
        let zInBlock = scratch.advanced(by: 3 * blockQwords)
        let tmpBlock = scratch.advanced(by: 4 * blockQwords)
        let addrBlock = scratch.advanced(by: 5 * blockQwords)

        var counter: UInt64 = 0
        let startJ = (pass == 0 && slice == 0) ? 2 : 0
        if dataIndependent {
            counter = 1
            nextAddresses(
                zero: zeroBlock,
                zIn: zInBlock,
                tmp: tmpBlock,
                addrOut: addrBlock,
                rBuf: rBuf,
                zBuf: zBuf,
                pass: pass,
                lane: lane,
                slice: slice,
                mPrime: mPrime,
                iterations: iterations,
                counter: counter
            )
        }

        let laneBase = lane * laneLength
        let lanesU64 = UInt64(parallelism)
        for j in startJ..<segmentLength {
            if dataIndependent && (j & 127) == 0 && j != 0 {
                counter &+= 1
                nextAddresses(
                    zero: zeroBlock,
                    zIn: zInBlock,
                    tmp: tmpBlock,
                    addrOut: addrBlock,
                    rBuf: rBuf,
                    zBuf: zBuf,
                    pass: pass,
                    lane: lane,
                    slice: slice,
                    mPrime: mPrime,
                    iterations: iterations,
                    counter: counter
                )
            }

            let col = slice * segmentLength + j
            let prevCol = (col == 0) ? (laneLength - 1) : (col - 1)
            let prevPtr = memory.advanced(by: (laneBase + prevCol) * blockQwords)

            let pseudoRand = dataIndependent ? addrBlock[j & 127] : prevPtr[0]
            let j1 = pseudoRand & 0xFFFF_FFFF
            let j2 = (pseudoRand >> 32) & 0xFFFF_FFFF
            let refLane = (pass == 0 && slice == 0) ? lane : Int(j2 % lanesU64)

            let startPos: Int
            let wSize: Int
            if pass == 0 {
                startPos = 0
                if slice == 0 || refLane == lane {
                    wSize = slice * segmentLength + j - 1
                } else {
                    wSize = slice * segmentLength - (j == 0 ? 1 : 0)
                }
            } else {
                startPos = (slice == syncPoints - 1) ? 0 : (slice + 1) * segmentLength
                if refLane == lane {
                    wSize = laneLength - segmentLength + j - 1
                } else {
                    wSize = laneLength - segmentLength - (j == 0 ? 1 : 0)
                }
            }

            let x = (j1 &* j1) >> 32
            let y = (UInt64(wSize) &* x) >> 32
            let zz = wSize - 1 - Int(y)
            let refCol = (startPos + zz) % laneLength

            let refPtr = memory.advanced(by: (refLane * laneLength + refCol) * blockQwords)
            let currPtr = memory.advanced(by: (laneBase + col) * blockQwords)

            compressBlock(
                xPtr: prevPtr,
                yPtr: refPtr,
                destPtr: currPtr,
                xorWithDest: pass != 0,
                rBuf: rBuf,
                zBuf: zBuf
            )
        }
    }

    private static func nextAddresses(
        zero: UnsafeMutablePointer<UInt64>,
        zIn: UnsafeMutablePointer<UInt64>,
        tmp: UnsafeMutablePointer<UInt64>,
        addrOut: UnsafeMutablePointer<UInt64>,
        rBuf: UnsafeMutablePointer<UInt64>,
        zBuf: UnsafeMutablePointer<UInt64>,
        pass: Int,
        lane: Int,
        slice: Int,
        mPrime: Int,
        iterations: Int,
        counter: UInt64
    ) {
        zIn.update(repeating: 0, count: blockQwords)
        zIn[0] = UInt64(pass)
        zIn[1] = UInt64(lane)
        zIn[2] = UInt64(slice)
        zIn[3] = UInt64(mPrime)
        zIn[4] = UInt64(iterations)
        zIn[5] = UInt64(typeArgon2id)
        zIn[6] = counter

        compressBlock(xPtr: zero, yPtr: zIn, destPtr: tmp, xorWithDest: false, rBuf: rBuf, zBuf: zBuf)
        compressBlock(xPtr: zero, yPtr: tmp, destPtr: addrOut, xorWithDest: false, rBuf: rBuf, zBuf: zBuf)
    }

    @inline(__always)
    private static func compressBlock(
        xPtr: UnsafeMutablePointer<UInt64>,
        yPtr: UnsafeMutablePointer<UInt64>,
        destPtr: UnsafeMutablePointer<UInt64>,
        xorWithDest: Bool,
        rBuf: UnsafeMutablePointer<UInt64>,
        zBuf: UnsafeMutablePointer<UInt64>
    ) {
        for k in 0..<blockQwords {
            let xored = xPtr[k] ^ yPtr[k]
            rBuf[k] = xored
            zBuf[k] = xored
        }

        for row in 0..<8 {
            let b = row << 4
            permute16(
                zBuf,
                b, b + 1, b + 2, b + 3,
                b + 4, b + 5, b + 6, b + 7,
                b + 8, b + 9, b + 10, b + 11,
                b + 12, b + 13, b + 14, b + 15
            )
        }

        for col in 0..<8 {
            let c = col << 1
            permute16(
                zBuf,
                c, c + 1, c + 16, c + 17,
                c + 32, c + 33, c + 48, c + 49,
                c + 64, c + 65, c + 80, c + 81,
                c + 96, c + 97, c + 112, c + 113
            )
        }

        if xorWithDest {
            for k in 0..<blockQwords {
                destPtr[k] ^= zBuf[k] ^ rBuf[k]
            }
        } else {
            for k in 0..<blockQwords {
                destPtr[k] = zBuf[k] ^ rBuf[k]
            }
        }
    }

    @inline(__always)
    private static func permute16(
        _ v: UnsafeMutablePointer<UInt64>,
        _ i0: Int, _ i1: Int, _ i2: Int, _ i3: Int,
        _ i4: Int, _ i5: Int, _ i6: Int, _ i7: Int,
        _ i8: Int, _ i9: Int, _ i10: Int, _ i11: Int,
        _ i12: Int, _ i13: Int, _ i14: Int, _ i15: Int
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

    @inline(__always)
    private static func gb(_ v: UnsafeMutablePointer<UInt64>, _ a: Int, _ b: Int, _ c: Int, _ d: Int) {
        var va = v[a]
        var vb = v[b]
        var vc = v[c]
        var vd = v[d]

        va = fBlaMka(va, vb)
        vd = Blake2b.rotr64(vd ^ va, 32)
        vc = fBlaMka(vc, vd)
        vb = Blake2b.rotr64(vb ^ vc, 24)

        va = fBlaMka(va, vb)
        vd = Blake2b.rotr64(vd ^ va, 16)
        vc = fBlaMka(vc, vd)
        vb = Blake2b.rotr64(vb ^ vc, 63)

        v[a] = va
        v[b] = vb
        v[c] = vc
        v[d] = vd
    }

    @inline(__always)
    private static func fBlaMka(_ x: UInt64, _ y: UInt64) -> UInt64 {
        let xy = (x & 0xFFFF_FFFF) &* (y & 0xFFFF_FFFF)
        return x &+ y &+ (xy &+ xy)
    }

    internal static func variableLengthHash(outLen: Int, input: [UInt8]) throws -> [UInt8] {
        if outLen <= 64 {
            return try Blake2b(outLen: outLen)
                .updateLeUInt32(UInt32(outLen))
                .update(input)
                .digest()
        }
        let r = (outLen + 31) / 32 - 2
        var out = [UInt8](repeating: 0, count: outLen)
        var v = try Blake2b(outLen: 64)
            .updateLeUInt32(UInt32(outLen))
            .update(input)
            .digest()
        defer {
            for i in 0..<v.count { v[i] = 0 }
        }
        for k in 0..<32 { out[k] = v[k] }
        if r >= 2 {
            for i in 2...r {
                let next = try Blake2b.hash(v, outLen: 64)
                for k in 0..<v.count { v[k] = 0 }
                v = next
                let base = (i - 1) * 32
                for k in 0..<32 { out[base + k] = v[k] }
            }
        }
        let remaining = outLen - 32 * r
        var tail = try Blake2b.hash(v, outLen: remaining)
        defer {
            for i in 0..<tail.count { tail[i] = 0 }
        }
        let tailBase = 32 * r
        for k in 0..<remaining { out[tailBase + k] = tail[k] }
        return out
    }

    @inline(__always)
    private static func writeLe32(_ value: UInt32, into bytes: inout [UInt8], offset: Int) {
        bytes[offset] = UInt8(truncatingIfNeeded: value)
        bytes[offset + 1] = UInt8(truncatingIfNeeded: value >> 8)
        bytes[offset + 2] = UInt8(truncatingIfNeeded: value >> 16)
        bytes[offset + 3] = UInt8(truncatingIfNeeded: value >> 24)
    }
}
