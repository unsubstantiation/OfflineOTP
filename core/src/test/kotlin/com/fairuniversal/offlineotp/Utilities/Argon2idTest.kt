package com.fairuniversal.offlineotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Argon2idTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format("%02x", it) }

    @Test
    fun blake2bMatchesRfc7693AndBoundaryLengths() {
        // RFC 7693 Appendix A: BLAKE2b-512("abc")
        val abc = Blake2b.hash("abc".toByteArray(Charsets.US_ASCII), 64)
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            hex(abc)
        )

        // BLAKE2b-512("") — exercises empty-input compression
        val empty = Blake2b.hash(ByteArray(0), 64)
        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419" +
                "d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            hex(empty)
        )

        // Exact 128-byte block boundary and multi-block (257-byte) streaming
        val block128 = ByteArray(128) { it.toByte() }
        val block257 = ByteArray(257) { (it * 7).toByte() }
        val stream = Blake2b(32)
        stream.update(block257, 0, 10)
        stream.update(block257, 10, 118)
        stream.update(block257, 128, 129)
        assertEquals(hex(Blake2b.hash(block257, 32)), hex(stream.digest()))
        assertEquals(64, Blake2b.hash(block128, 64).size)
    }

    @Test
    fun argon2idMatchesRfc9106Section53TestVector() {
        val password = ByteArray(32) { 0x01 }
        val salt = ByteArray(16) { 0x02 }
        val secret = ByteArray(8) { 0x03 }
        val ad = ByteArray(12) { 0x04 }

        val tag = Argon2id.derive(
            password = password,
            salt = salt,
            memoryKiB = 32,
            iterations = 3,
            parallelism = 4,
            tagLength = 32,
            secret = secret,
            associatedData = ad
        )
        assertEquals(
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
            hex(tag)
        )

        // Unkeyed / empty-AD counterpart cross-checked against reference libargon2 v1.3
        val unkeyed = Argon2id.derive(
            password = password,
            salt = salt,
            memoryKiB = 32,
            iterations = 3,
            parallelism = 4,
            tagLength = 32
        )
        assertEquals(
            "03aab965c12001c9d7d0d2de33192c0494b684bb148196d73c1df1acaf6d0c2e",
            hex(unkeyed)
        )
    }

    @Test
    fun argon2idMatchesLibargon2AcrossMultiAddressBlockAndProductionParameters() {
        val salt = ByteArray(16) { it.toByte() }

        // Segment length 256 (> 128): exercises multi-address-block counter advancement in slices 0 and 1.
        val multiAddr = Argon2id.deriveFromPassword(
            password = "golden-interop-password",
            salt = salt,
            memoryKiB = 4096,
            iterations = 2,
            parallelism = 4,
            tagLength = 32
        )
        assertEquals(
            "07adf15198ab35e7cce6183e2fabab30c38de1a9310142d83c6efb49c1e62ff6",
            hex(multiAddr)
        )

        // Production profile: 64 MiB (65536 KiB), t = 3, p = 4, 256-bit output.
        val production = Argon2id.deriveFromPassword(
            password = "golden-interop-password",
            salt = salt,
            memoryKiB = Constants.ARGON2_MEMORY_KIB,
            iterations = Constants.ARGON2_ITERATIONS,
            parallelism = Constants.ARGON2_PARALLELISM,
            tagLength = Constants.ARGON2_HASH_LENGTH
        )
        assertEquals(
            "e95b05c8927f84f547af8fb2f233b07e79d60e858534be027971f57fd99d6f03",
            hex(production)
        )

        val credentials = CryptoPrimitives.generateArgon2idCredentials(
            password = "golden-interop-password",
            salt = salt
        )
        assertEquals(32, credentials.key!!.size)
        assertEquals(32, credentials.password!!.size)
        assertEquals(
            "931bc0ec226b1e7407439637dfa1b3e8b319b1193da7f3df11f13be52c540c5b",
            hex(credentials.key)
        )
        assertEquals(
            "5527fcaf7633c24e07a358b501003a1ecb5232698349fee4144714f93d31c399",
            hex(credentials.password)
        )
    }

    @Test
    fun invalidArgon2idParametersAreRejectedBeforeAllocation() {
        val salt = ByteArray(16) { 1 }
        val tooLong = "x".repeat(InputPolicy.MAX_TEXT_CHARS + 1)
        for (invalid in listOf<() -> Unit>(
            { Argon2id.deriveFromPassword(null, salt) },
            { Argon2id.deriveFromPassword(tooLong, salt) },
            { Argon2id.deriveFromPassword("pw", null) },
            { Argon2id.deriveFromPassword("pw", ByteArray(7)) },
            { Argon2id.deriveFromPassword("pw", salt, memoryKiB = 31, iterations = 3, parallelism = 4) },
            { Argon2id.deriveFromPassword("pw", salt, memoryKiB = Argon2id.MAX_MEMORY_KIB + 1, iterations = 1, parallelism = 1) },
            { Argon2id.deriveFromPassword("pw", salt, memoryKiB = 64, iterations = 0, parallelism = 1) },
            { Argon2id.deriveFromPassword("pw", salt, memoryKiB = 64, iterations = Argon2id.MAX_ITERATIONS + 1, parallelism = 1) },
            { Argon2id.deriveFromPassword("pw", salt, memoryKiB = 64, iterations = 1, parallelism = 0) },
            { Argon2id.deriveFromPassword("pw", salt, memoryKiB = 64, iterations = 1, parallelism = Argon2id.MAX_PARALLELISM + 1) },
            { Argon2id.deriveFromPassword("pw", salt, memoryKiB = 262_144, iterations = 5, parallelism = 4) }
        )) {
            try {
                invalid()
                throw AssertionError("invalid Argon2id parameters accepted")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
        assertTrue(CryptoPrimitives.generateSymmetricKeyArgon2id("pw", salt, 32, 1, 4).encoded.size == 32)
    }
}
