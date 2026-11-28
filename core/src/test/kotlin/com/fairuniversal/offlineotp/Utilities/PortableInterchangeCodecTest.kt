package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableInterchangeCodecTest {
    private val codec = InterchangeCodec()
    private val seed = "JBSWY3DPEHPK3PXP"
    @Test fun csvRetainsQuotedNewlinesUnicodeAndEveryColumn() {
        val source = "\uFEFFname,url,username,password,note\r\nExample,https://example.org,alice,\"p,\"\"w\"\"\",\"café\nsecond line\"\r\n"
        val result = codec.csv(source.encodeToByteArray(), false)
        assertNull(result.error); assertEquals(5, result.columns.size)
        assertEquals("p,\"w\"", result.rows.single().fields[3])
        assertEquals("café\nsecond line", result.rows.single().fields[4])
        val exported = codec.exportCsv(result.columns, result.rows)!!
        val reopened = codec.csv(exported, false)
        assertEquals(result.columns, reopened.columns); assertEquals(result.rows.single().fields, reopened.rows.single().fields)
        exported.fill(0)
        assertFalse(result.toString().contains("alice")); assertFalse(result.rows.single().toString().contains("alice"))
    }
    @Test fun csvRejectsAmbiguityPartialRowsAndMalformedUnicode() {
        for (text in listOf("name,url,username,password\na,b,c", "name,url,username,password,URL\na,b,c,d,e",
            "name,url,username,password\na,b,c,\"unclosed", "name,url,username,password\na,b,c,\"x\"suffix")) {
            val result = codec.csv(text.encodeToByteArray(), false)
            assertNotNull(result.error); assertTrue(result.rows.isEmpty())
        }
        assertEquals("INVALID_UTF8", codec.csv(byteArrayOf(0xc3.toByte(), 0x28), false).error)
        assertNull(codec.exportCsv(listOf("url"), listOf(CsvRecord(listOf("x", "y")))))
    }
    @Test fun thunderbirdAliasesRemainDistinctFromBrowserHeaders() {
        val data = "hostname,user,pass,notes\nimap://example.org,alice,secret,preserved\n".encodeToByteArray()
        assertNull(codec.csv(data, true).error)
        assertEquals("UNSUPPORTED_FORMAT", codec.csv(data, false).error)
    }
    @Test fun provisioningDecodesExactlyOnceAndOwnsItsSeed() {
        val reply = codec.otp("otpauth://hotp/Acme:alice%2Bwork?secret=$seed&counter=42&issuer=Acme&tags=work&tags=mail")
        val account = reply.value!!
        assertEquals(EntryRecordKind.HOTP, account.kind); assertEquals(42L, account.counter)
        assertEquals("alice+work", account.label); assertEquals(listOf("work", "mail"), account.tags)
        val copy = account.copySecret(); account.destroy(); assertTrue(copy.any { it != 0.toByte() }); copy.fill(0)
        assertFalse(reply.toString().contains(seed)); assertFalse(account.toString().contains("alice"))
    }
    @Test fun sharedProvisioningExportPreservesFieldOrderAndPercentEncoding() {
        val parsed = codec.otp("otpauth://hotp/alice%2Bwork?secret=$seed&counter=42&issuer=Acme%20Inc&digits=8&algorithm=SHA256&tags=a%26b").value!!
        val bytes = parsed.copySecret()
        try {
            val exported = ProvisioningCodec.encode(parsed.kind.name, bytes, parsed.period, parsed.digits, parsed.counter,
                parsed.issuer, parsed.label, parsed.algorithm.name, parsed.tags)!!
            assertTrue(exported.startsWith("otpauth://hotp/alice%2Bwork?secret=$seed&issuer=Acme%20Inc&counter=42&digits=8&algorithm=SHA256&tags=a%26b"))
            val reopened = codec.otp(exported).value!!
            assertEquals(parsed.label, reopened.label); assertEquals(parsed.counter, reopened.counter); reopened.destroy()
        } finally { bytes.fill(0); parsed.destroy() }
    }
    @Test fun csvAndMaterialBoundsRefuseOversizedOutputRatherThanTruncatingSecrets() {
        assertNotNull(codec.csv(ByteArray(4 * 1024 * 1024 + 1), false).error)
        assertNull(codec.exportCsv(listOf("password"), List(2001) { CsvRecord(listOf("x")) }))
        val material = CredentialMaterialCodec()
        assertNull(material.sshEncode("", "x".repeat(32769)))
        assertNull(material.passwordEncode(listOf("url", "username", "password"), listOf("x", "y", "z".repeat(CredentialMaterialCodec.MAX_PASSWORD_FIELD_BYTES + 1))))
    }
    @Test fun passwordFieldLimitIsUtf8BytesAndRetainsOverallPayloadBound() {
        val material = CredentialMaterialCodec()
        val limit = CredentialMaterialCodec.MAX_PASSWORD_FIELD_BYTES
        val names = listOf("url", "username", "password", "linscp_tls_key_b64")
        val values = listOf("sftp://host", "alice", "secret", "a".repeat(limit))
        val encoded = material.passwordEncode(names, values)!!
        try {
            assertEquals(values, material.passwordDecode(encoded)!!.values)
            // A hostile wire payload bypassing our encoder must still be rejected.
            val oversized = encoded + 'a'.code.toByte()
            val lengthOffset = encoded.size - limit - 4
            for (i in 0..3) oversized[lengthOffset + i] = ((limit + 1) ushr (24 - i * 8)).toByte()
            try { assertNull(material.passwordDecode(oversized)) } finally { oversized.fill(0) }
        } finally { encoded.fill(0) }
        assertNull(material.passwordEncode(names, values.dropLast(1) + "a".repeat(limit + 1)))
        // Under the character limit but above the byte limit must also be refused.
        assertNull(material.passwordEncode(names, values.dropLast(1) + "λ".repeat(limit / 2 + 1)))
        val unicode = material.passwordEncode(names, values.dropLast(1) + "λ".repeat(limit / 2))!!
        try { assertNotNull(material.passwordDecode(unicode)) } finally { unicode.fill(0) }
        val manyNames = listOf("url", "username", "password") + (1..8).map { "linscp_blob_$it" }
        assertNull(material.passwordEncode(manyNames, List(manyNames.size) { "a".repeat(limit) }))
        // CSV has its own deliberately smaller field bound.
        assertEquals(64 * 1024, com.fairuniversal.offlineotp.domain.BrowserCsvCodec.MAX_FIELD_CHARS)
    }
    @Test fun provisioningRejectsUnsafeUriAndNoncanonicalSeedsWithoutFallback() {
        for (input in listOf("otpauth://totp@evil/x?secret=$seed", "otpauth://totp:80/x?secret=$seed",
            "otpauth://totp/x?secret=$seed#fragment", "otpauth://totp/a%FF?secret=$seed",
            "otpauth://totp/x?secret=$seed&secret=$seed", "otpauth://totp/x?secret=JBSWY3DPEHPK3PX")) {
            assertNull(codec.otp(input).value); assertNotNull(codec.otp(input).error)
        }
    }
    @Test fun passwordsAndSshShareStrictFramingAndPreserveMetadata() {
        val material = CredentialMaterialCodec()
        val encoded = material.passwordEncode(listOf("URL", "username", "password", "note"), listOf("https://x", "alice", "secret", "line\nλ"))!!
        val fields = material.passwordDecode(encoded)!!
        assertEquals("line\nλ", fields.values.last()); assertEquals("URL", fields.names.first())
        assertNull(material.passwordDecode(encoded + 0.toByte()))
        assertNull(material.passwordEncode(listOf("url", "URL", "password"), listOf("x", "y", "z")))
        val ssh = material.sshEncode(" public\n", "private\nkey ")!!
        assertEquals(listOf(" public\n", "private\nkey "), material.sshDecode(ssh)!!.values)
        assertNull(material.sshDecode(byteArrayOf(0x7f, -1, -1, -1, 0, 0, 0, 0)))
        encoded.fill(0); ssh.fill(0)
        assertFalse(fields.toString().contains("secret"))
    }
    @Test fun preferencesAreValidatedCompletelyBeforeAnyNativeMutation() {
        val policy = PreferencesInterchangeCodec()
        val rules = listOf(PreferenceRule("idle", "INTEGER"), PreferenceRule("backup", "SECRET"))
        fun parse(settings: String, platform: String = "desktop") = policy.decode(
            """{"format":"offlineotp.preferences","version":1,"platform":"desktop","settings":$settings}""", platform, rules, listOf("device"))
        val valid = parse("""{"idle":60,"backup":"secret","new":{"future":true},"device":"must not apply"}""")
        assertNull(valid.error); assertEquals(listOf("backup", "idle"), valid.accepted)
        assertEquals(listOf("new"), valid.unknown); assertEquals(listOf("device"), valid.refused); assertTrue(valid.includesSecrets)
        for (bad in listOf("1.0", "\"60\"", "2147483648", "true")) {
            val failed = parse("{\"idle\":$bad,\"backup\":\"secret\"}")
            assertNotNull(failed.error); assertTrue(failed.accepted.isEmpty()); assertNull(failed.json)
        }
        assertNotNull(parse("""{"idle":60,"idle":5}""").error)
        assertEquals("PLATFORM", parse("{}", "ios").error)
        assertFalse(valid.toString().contains("secret"))
    }
    @Test fun iniPreservesUnknownFieldsEscapesAndLastDuplicateSectionInOriginalOrder() {
        val sections = LinScpSessionCodec().parse("""[First]
password=old
[Second]
host=server
[First]
password=new\nline
unknown=keep\q
""")!!
        assertEquals(listOf("First", "Second"), sections.map { it.name })
        assertEquals("new\nline", sections.first().fields["password"])
        assertEquals("keep\\q", sections.first().fields["unknown"])
        assertFalse(sections.first().toString().contains("new"))
        assertNull(LinScpSessionCodec().parse("host=orphan"))
    }
    @Test fun linScpRefusesWrongFamilyTruncatedFramingAndMalformedBundles() {
        val policy = CredentialDocumentCodec()
        assertTrue(policy.encryptedLinScp("LSCPVLT2".encodeToByteArray(), 52))
        assertFalse(policy.encryptedLinScp("LSCPVLT2".encodeToByteArray(), 51))
        assertFalse(policy.encryptedLinScp("LSCPVLT1".encodeToByteArray(), 100))
        assertNull(policy.linScp("[]", false)); assertNull(policy.linScp("""[{"type":"SSH_KEY","label":"bad","secret":"AAAA"}]""", false))
        assertNull(policy.linScp("""{"version":2,"sessions_ini":"[x]"}""", true))
        assertNotNull(policy.linScp("""{"version":1,"sessions_ini":"[x]","future":[1,true]}""", true))
    }
    @Test fun pendingWorkflowAndBatchMetadataCannotCrossSessionsOrDeliverTwice() {
        val preview = InterchangeWorkflow(8)
        assertFalse(preview.consume(8, true)); assertTrue(preview.preview(1))
        assertFalse(preview.consume(9, true)); assertFalse(preview.consume(8, true))
        val batch = MigrationBatchPolicy(8)
        assertEquals(PortableBatchStatus.ADDED, batch.add(1, 2, 1, -42, 1, 8, true))
        assertEquals(PortableBatchStatus.DUPLICATE, batch.add(1, 2, 1, -42, 1, 8, true))
        assertEquals(PortableBatchStatus.DIFFERENT_EXPORT, batch.add(1, 2, 0, 1, 1, 8, true))
        assertEquals(PortableBatchStatus.COMPLETE, batch.add(1, 2, 0, -42, 1, 8, true))
        assertTrue(batch.take(8, true)); assertFalse(batch.take(8, true))
        val stale = MigrationBatchPolicy(8)
        assertEquals(PortableBatchStatus.STALE, stale.add(1, 1, 0, 0, 1, 9, true)); assertTrue(stale.closed)
    }
}
