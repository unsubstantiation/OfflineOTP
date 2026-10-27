/*
 * The interoperable example payload below is from lennyerik/google-authenticator-export,
 * src/test/proto/test_data.bin, Copyright (c) 2024 lennyerik, used under the MIT License:
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random

class GoogleAuthenticatorMigrationTest {
    // An independent export fixture; the keys are deliberately public test values, not real accounts.
    private val exporterSample = "CikKCkhlbGxvId6tvu8SCHRlc3R1c2VyGgtleGFtcGxlLmNvbSABKAEwAgo8" +
        "CgpXb3JsZCEgICAgEhx0ZXN0LWdpdGh1Yi51c2VyQGV4YW1wbGUuY29tGgpnaXRodWIuY29tIAEoATACCjcKCldv" +
        "cmxkISAgICESD2Fub3RoZXJ0ZXN0dXNlchoSc29tZWV4YW1wbGVzZXJ2aWNlIAEoATACCjIKCldvcmxkISAgICIS" +
        "CmZvdXJ0aHVzZXIaEnNvbWVleGFtcGxlc2VydmljZSABKAEwAhACGAEgAA=="

    private fun success(uri: String): MigrationPayload {
        val result = GoogleAuthenticatorMigration.parse(uri)
        assertTrue("expected a valid migration payload", result is MigrationParseResult.Success)
        return (result as MigrationParseResult.Success).payload
    }

    private fun failure(uri: String?): MigrationError {
        val result = GoogleAuthenticatorMigration.parse(uri)
        assertTrue("expected rejection", result is MigrationParseResult.Failure)
        return (result as MigrationParseResult.Failure).error
    }

    @Test
    fun parsesIndependentGoogleExportFixture() {
        success("otpauth-migration://offline?data=$exporterSample").use { payload ->
            assertEquals(2, payload.version)
            assertEquals(1, payload.batchSize)
            assertEquals(0, payload.batchIndex)
            assertEquals(4, payload.count)
            val accounts = payload.takeAccounts()
            try {
                assertEquals(listOf("testuser", "test-github.user@example.com", "anothertestuser", "fourthuser"),
                    accounts.map { it.label })
                assertEquals(listOf("example.com", "github.com", "someexampleservice", "someexampleservice"),
                    accounts.map { it.issuer })
                assertArrayEquals(byteArrayOf(72, 101, 108, 108, 111, 33, 0xde.toByte(), 0xad.toByte(),
                    0xbe.toByte(), 0xef.toByte()), accounts[0].copySecret())
                assertTrue(accounts.all { it.kind == OtpKind.TOTP && it.algorithm == OtpHashAlgorithm.SHA1 && it.digits == 6 })
            } finally { accounts.forEach { it.close() } }
        }
    }

    @Test
    fun importsRealTokenParametersAndCopiesSeedsIntoEntries() {
        val seed = "12345678901234567890".toByteArray()
        val totp = account(seed, "Example:alice", "Example", algorithm = 1, digits = 2, type = 2)
        val hotp = account(seed, "Service:bob", "", algorithm = 1, digits = 1, type = 1, counter = 0)
        val sha256 = account(seed, "carol", "Other", algorithm = 2, digits = 2, type = 2)
        val sha512 = account(seed, "dan", "Other", algorithm = 3, digits = 1, type = 2)
        success(uri(payload(listOf(totp, hotp, sha256, sha512)))).use { parsed ->
            val values = parsed.takeAccounts()
            val first = Entry(values[0])
            val second = Entry(values[1])
            try {
                assertEquals("Example", first.getIssuer())
                assertEquals("alice", first.getLabel())
                assertEquals(8, first.getDigits())
                values[0].close() // Entry owns an independent copy.
                assertArrayEquals(seed, first.getSecret())
                assertEquals(94287082, TokenCalculator.TOTP_RFC6238(first.getSecret()!!, 30, 59, 8,
                    TokenCalculator.HashAlgorithm.SHA1))
                assertEquals("Service", second.getIssuer())
                assertEquals("bob", second.getLabel())
                assertEquals(Entry.OTPType.HOTP, second.getType())
                assertEquals(0L, second.getCounter())
                assertEquals("755224", TokenCalculator.HOTP(second.getSecret()!!, second.getCounter(), 6,
                    TokenCalculator.HashAlgorithm.SHA1))
                assertEquals(OtpHashAlgorithm.SHA256, values[2].algorithm)
                assertEquals(OtpHashAlgorithm.SHA512, values[3].algorithm)
            } finally {
                first.destroy()
                second.destroy()
                values.forEach { it.close() }
            }
        }
    }

    @Test
    fun acceptsPercentEncodedLiteralAndUrlSafeBase64AndDefaults() {
        val raw = (0..255).asSequence().map { byte ->
            payload(listOf(account(byteArrayOf(byte.toByte(), 0xfb.toByte(), 0xff.toByte()), "alice")))
        }.first { Base64.getEncoder().encodeToString(it).contains('+') &&
            Base64.getEncoder().encodeToString(it).contains('/') }
        val encoded = Base64.getEncoder().encodeToString(raw)
        val escaped = encoded.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        for (data in listOf(encoded, escaped, urlSafe)) {
            success("otpauth-migration://offline/?data=$data").use { payload ->
                val values = payload.takeAccounts()
                try {
                    assertEquals(OtpKind.TOTP, values[0].kind)
                    assertEquals(OtpHashAlgorithm.SHA1, values[0].algorithm)
                    assertEquals(6, values[0].digits)
                    assertEquals("alice", values[0].label)
                } finally { values.forEach { it.close() } }
            }
        }
    }

    @Test
    fun omittedProto3DefaultsAreHandled() {
        val bareAccount = fieldBytes(1, byteArrayOf(1, 2, 3)) + fieldBytes(2, "only-name".toByteArray())
        success(uri(fieldBytes(1, bareAccount))).use { payload ->
            assertEquals(0, payload.version)
            assertEquals(1, payload.batchSize)
            assertEquals(0, payload.batchIndex)
            val accounts = payload.takeAccounts()
            try {
                assertEquals(OtpKind.TOTP, accounts[0].kind)
                assertEquals(OtpHashAlgorithm.SHA1, accounts[0].algorithm)
                assertEquals(6, accounts[0].digits)
                assertEquals("only-name", accounts[0].label)
            } finally { accounts.forEach { it.close() } }
        }
    }

    @Test
    fun rejectsMalformedUrisAndProtobufWithoutLeakingInput() {
        assertEquals(MigrationError.INVALID_URI, failure(null))
        assertEquals(MigrationError.INVALID_URI, failure("otpauth-migration://online?data=AA"))
        assertEquals(MigrationError.INVALID_URI, failure("otpauth-migration://offline/path?data=AA"))
        assertEquals(MigrationError.INVALID_URI, failure("otpauth-migration://offline?data=AA&data=AA"))
        assertEquals(MigrationError.INVALID_URI, failure("otpauth-migration://offline?data=%zz"))
        assertEquals(MigrationError.TOO_LARGE, failure("otpauth-migration://offline?data=" + "A".repeat(InputPolicy.MAX_URI_CHARS)))
        assertEquals(MigrationError.INVALID_DATA, failure("otpauth-migration://offline?data=***"))
        val invalid = listOf(
            byteArrayOf(0),                                      // field number zero
            byteArrayOf(0x0a, 0x7f, 0x01),                      // truncated nested message
            ByteArray(11) { 0x80.toByte() },                    // overflowing varint
            fieldBytes(1, fieldBytes(1, byteArrayOf())),         // empty secret
            fieldBytes(1, fieldBytes(1, byteArrayOf(1)) + fieldBytes(1, byteArrayOf(2))), // duplicate seed
            fieldBytes(1, fieldBytes(1, byteArrayOf(1)) + fieldBytes(2, byteArrayOf(0xc3.toByte()))), // bad UTF-8
            fieldBytes(1, fieldBytes(1, byteArrayOf(1)) + fieldBytes(8, byteArrayOf(0xc3.toByte()))), // bad ID
            fieldBytes(1, fieldBytes(1, byteArrayOf(1)) + fieldVarint(8, 1)), // wrong ID wire type
            fieldBytes(1, fieldBytes(1, byteArrayOf(1)) + fieldBytes(8, byteArrayOf(1)) + fieldBytes(8, byteArrayOf(2))),
            fieldVarint(1, 1),                                  // wrong wire type for account
            fieldBytes(1, fieldBytes(1, ByteArray(InputPolicy.MAX_SECRET_BYTES + 1) { 1 })),
            payload(listOf(account(byteArrayOf(1), "a")), size = 2, index = 2),
            payload(listOf(account(byteArrayOf(1), "a")), size = InputPolicy.MAX_ENTRIES + 1),
            payload(listOf(account(byteArrayOf(1), "a", type = 1, counter = -1))),
            payload(listOf(account(byteArrayOf(1), "a", type = 1, counter = Long.MAX_VALUE)))
        )
        for (binary in invalid) assertEquals(MigrationError.INVALID_DATA, failure(uri(binary)))
        val longName = fieldBytes(1, fieldBytes(1, byteArrayOf(1)) +
            fieldBytes(2, ByteArray(InputPolicy.MAX_TEXT_CHARS + 1) { 65 }))
        assertEquals(MigrationError.TOO_LARGE, failure(uri(longName)))
        val result = GoogleAuthenticatorMigration.parse("otpauth-migration://offline?data=private-secret")
        assertFalse(result.toString().contains("private-secret"))
    }

    @Test
    fun rejectsUnsupportedAccountsWithoutSilentlyImportingTheOthers() {
        val valid = account(byteArrayOf(1), "valid")
        for (unsupported in listOf(
            account(byteArrayOf(2), "bad", algorithm = 4),
            account(byteArrayOf(2), "bad", algorithm = 9),
            account(byteArrayOf(2), "bad", digits = 9),
            account(byteArrayOf(2), "bad", type = 9),
            account(byteArrayOf(2), "bad") + fieldVarint(12, 123)
        )) assertEquals(MigrationError.UNSUPPORTED, failure(uri(payload(listOf(valid, unsupported)))))
        assertEquals(MigrationError.UNSUPPORTED, failure(uri(payload(listOf(valid), version = 3))))
        assertEquals(MigrationError.UNSUPPORTED, failure(uri(payload(listOf(valid)) + fieldBytes(9, byteArrayOf(1)))))
    }

    @Test
    fun acceptsAccountIdentifiersSevenDigitsAndLargeHotpCounters() {
        val seed = byteArrayOf(0x01, 0x02, 0x03)
        val identifier = fieldBytes(8, "some-id".toByteArray())
        val hotp = account(seed, "alice", type = 1, counter = 9_000_000_000L) + identifier
        val sevenDigits = account("12345678901234567890".toByteArray(), "bob", digits = 3) + identifier
        success(uri(payload(listOf(hotp, sevenDigits)))).use { result ->
            val values = result.takeAccounts()
            try {
                assertEquals(9_000_000_000L, values[0].counter)
                assertEquals(7, values[1].digits)
                val entry = Entry(values[1])
                try {
                    assertTrue(entry.updateOTPAt(59L, false))
                    assertEquals("4287082", entry.getCurrentOTP()) // RFC 6238 vector truncated to seven digits
                } finally { entry.destroy() }
            } finally { values.forEach { it.close() } }
        }
    }

    @Test
    fun multipartExportsAreSessionBoundOrderedAndRejectMixingOrDuplicateParts() {
        fun part(index: Int, id: Int, label: String): MigrationPayload =
            success(uri(payload(listOf(account(byteArrayOf((index + 1).toByte()), label)), size = 3, index = index, id = id)))
        val collector = MigrationBatchCollector(42)
        assertEquals(MigrationBatchStatus.ADDED, collector.add(part(2, -17, "third"), 42, true))
        assertEquals(MigrationBatchStatus.ADDED, collector.add(part(0, -17, "first"), 42, true))
        assertEquals(2, collector.received)
        assertEquals(3, collector.expected)
        assertEquals(MigrationBatchStatus.DUPLICATE, collector.add(part(0, -17, "replacement"), 42, true))
        assertEquals(MigrationBatchStatus.DIFFERENT_EXPORT, collector.add(part(1, 18, "foreign"), 42, true))
        assertEquals(MigrationBatchStatus.COMPLETE, collector.add(part(1, -17, "second"), 42, true))
        val all = requireNotNull(collector.takeAccounts(42, true))
        try { assertEquals(listOf("first", "second", "third"), all.map { it.label }) }
        finally { all.forEach { it.close() }; collector.close() }
        assertNull(collector.takeAccounts(42, true))
    }

    @Test
    fun closingOrRelockingDestroysAllBufferedSecrets() {
        val buffered = otp("buffered")
        val rejected = otp("rejected")
        val collector = MigrationBatchCollector(7)
        assertEquals(MigrationBatchStatus.ADDED,
            collector.add(MigrationPayload(1, 2, 0, 9, listOf(buffered)), 7, true))
        assertEquals(MigrationBatchStatus.STALE,
            collector.add(MigrationPayload(1, 2, 1, 9, listOf(rejected)), 8, true))
        assertThrows(IllegalStateException::class.java) { buffered.copySecret() }
        assertThrows(IllegalStateException::class.java) { rejected.copySecret() }

        val cancelled = otp("cancelled")
        val next = MigrationBatchCollector(9)
        next.add(MigrationPayload(1, 2, 0, 1, listOf(cancelled)), 9, true)
        next.close()
        next.close()
        assertThrows(IllegalStateException::class.java) { cancelled.copySecret() }
    }

    @Test
    fun cumulativeBatchLimitClosesPreviouslyBufferedSecrets() {
        val buffered = otp("buffered")
        val overflow = otp("overflow")
        val collector = MigrationBatchCollector(4)
        val first = MigrationPayload(2, 2, 0, 3, List(InputPolicy.MAX_ENTRIES) { buffered })
        assertEquals(MigrationBatchStatus.ADDED, collector.add(first, 4, true))
        assertEquals(MigrationBatchStatus.TOO_MANY,
            collector.add(MigrationPayload(2, 2, 1, 3, listOf(overflow)), 4, true))
        assertThrows(IllegalStateException::class.java) { buffered.copySecret() }
        assertThrows(IllegalStateException::class.java) { overflow.copySecret() }
    }

    @Test
    fun pendingConfirmationClearsOnCancellationOrSessionChange() {
        val first = Entry("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP")
        val second = Entry("otpauth://totp/bob?secret=JBSWY3DPEHPK3PXP")
        val pending = PendingMigrationImport(listOf(first, second), 3)
        assertEquals("PendingMigrationImport(redacted)", pending.toString())
        assertNull(pending.take(4, true))
        assertNull(first.getSecret())
        assertNull(second.getSecret())
        assertNull(pending.take(3, true))
        pending.close()

        val accepted = Entry("otpauth://totp/ok?secret=JBSWY3DPEHPK3PXP")
        val confirmed = PendingMigrationImport(listOf(accepted), 5)
        assertTrue(requireNotNull(confirmed.take(5, true))[0] === accepted)
        confirmed.close()
        assertTrue(accepted.getSecret() != null)
        accepted.destroy()
    }

    @Test
    fun randomBoundedPayloadsNeverThrow() {
        val random = Random(77)
        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(96))
            random.nextBytes(bytes)
            val result = GoogleAuthenticatorMigration.parse(uri(bytes))
            if (result is MigrationParseResult.Success) result.payload.close()
        }
    }

    private fun otp(label: String): ValidatedOtp = ValidatedOtp.create(OtpKind.TOTP, byteArrayOf(1, 2, 3),
        30, 6, 0, "Example", label, OtpHashAlgorithm.SHA1, emptyList())

    private fun account(secret: ByteArray, name: String, issuer: String = "", algorithm: Int = 0,
                        digits: Int = 0, type: Int = 0, counter: Long = 0): ByteArray =
        fieldBytes(1, secret) + fieldBytes(2, name.toByteArray()) + fieldBytes(3, issuer.toByteArray()) +
            fieldVarint(4, algorithm.toLong()) + fieldVarint(5, digits.toLong()) +
            fieldVarint(6, type.toLong()) + fieldVarint(7, counter)

    private fun payload(accounts: List<ByteArray>, version: Int = 2, size: Int = 1,
                        index: Int = 0, id: Int = 0): ByteArray =
        accounts.fold(byteArrayOf()) { all, account -> all + fieldBytes(1, account) } +
            fieldVarint(2, version.toLong()) + fieldVarint(3, size.toLong()) +
            fieldVarint(4, index.toLong()) + fieldVarint(5, id.toLong())

    private fun uri(bytes: ByteArray): String = "otpauth-migration://offline?data=" +
        Base64.getEncoder().encodeToString(bytes)

    private fun fieldBytes(field: Int, bytes: ByteArray): ByteArray =
        varint((field * 8 + 2).toLong()) + varint(bytes.size.toLong()) + bytes

    private fun fieldVarint(field: Int, value: Long): ByteArray = varint((field * 8).toLong()) + varint(value)

    private fun varint(input: Long): ByteArray {
        var value = input
        val out = ByteArrayOutputStream()
        do {
            val next = (value and 0x7f).toInt()
            value = value ushr 7
            out.write(if (value == 0L) next else next or 0x80)
        } while (value != 0L)
        return out.toByteArray()
    }
}
