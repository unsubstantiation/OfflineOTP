package org.shadowice.flocke.andotp.Database

import org.apache.commons.codec.binary.Base32
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupCodec
import org.shadowice.flocke.andotp.Utilities.SshConnection
import org.shadowice.flocke.andotp.Utilities.SshKeyMaterial

class SshKeyEntryTest {
    private val publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA test@example\n"
    private val privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "X".repeat(4096) +
        "\n-----END OPENSSH PRIVATE KEY-----\n"
    private fun entry(pub: String = publicKey, priv: String = privateKey) =
        Entry.sshKey(pub, priv, "", "server", arrayListOf("work"))

    private fun connection() = SshConnection.of("example.com", 2222, "alice", "correct horse")!!

    @Test fun eitherOrBothFieldsRoundTripThroughEncryptedBackup() {
        for ((pub, priv) in listOf(publicKey to "", "" to privateKey, publicKey to privateKey)) {
            val original = entry(pub, priv)
            val json = VaultJson.entriesToString(listOf(original))
            val backup = EncryptedBackupCodec.seal("test-password", json)
            val restored = VaultJson.stringToEntries(EncryptedBackupCodec.open("test-password", backup)).single()
            assertEquals(Entry.OTPType.SSH_KEY, restored.getType())
            assertEquals(pub, restored.getSshKeys().publicKey)
            assertEquals(priv, restored.getSshKeys().privateKey)
            assertEquals(listOf("work"), restored.getTags())
            assertFalse(restored.updateOTP(false))
            assertFalse(restored.isTimeBased())
            assertFalse(restored.hasCopyableToken())
            try { restored.toUriString(); throw AssertionError("SSH key exported as OTP") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun boundariesAndInvalidUnicodeAreRejectedWithoutChangingKeys() {
        assertEquals(SshKeyMaterial.MAX_KEY_BYTES, entry("x".repeat(SshKeyMaterial.MAX_KEY_BYTES), "").getSshKeys().publicKey.length)
        for ((pub, priv) in listOf("" to "", " " to "\n", "x".repeat(SshKeyMaterial.MAX_KEY_BYTES + 1) to "",
                "" to "🔐".repeat(SshKeyMaterial.MAX_KEY_BYTES / 4 + 1), "\uD800" to "")) {
            try { entry(pub, priv); throw AssertionError("invalid keys accepted") }
            catch (_: IllegalArgumentException) { }
        }
        val original = entry()
        try { original.setSshKeys("", ""); throw AssertionError("empty keys accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(privateKey, original.getSshKeys().privateKey)
        assertEquals("SshKeyMaterial.Keys(redacted)", original.getSshKeys().toString())
    }

    @Test fun malformedOrTruncatedSshDocumentsFailClosed() {
        val invalid = listOf(byteArrayOf(0, 0, 0, 9), byteArrayOf(0, 0, 0, 1, 0xc3.toByte(), 0, 0, 0, 0),
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0), ByteArray(SshKeyMaterial.MAX_ENCODED_BYTES + 1))
        for (bytes in invalid) {
            val json = JSONObject().put("type", "SSH_KEY").put("label", "server")
                .put("secret", Base32().encodeAsString(bytes))
            assertTrue(EntryJson.parse(json) is EntryJsonResult.Failure)
        }
    }

    @Test fun failedEditRestoresBothFields() {
        val original = entry()
        try {
            original.applyEdit { it.setSshKeys("another", "another private"); it.setLabel("") }
            throw AssertionError("invalid edit accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(publicKey, original.getSshKeys().publicKey)
        assertEquals(privateKey, original.getSshKeys().privateKey)
    }

    @Test fun connectionFieldsSurviveTheEncryptedVaultAndBackup() {
        val original = Entry.sshKey(publicKey, privateKey, "work", "server", arrayListOf("ops"), connection())
        val json = VaultJson.entriesToString(listOf(original))
        val backup = EncryptedBackupCodec.seal("test-password", json)
        val restored = VaultJson.stringToEntries(EncryptedBackupCodec.open("test-password", backup)).single()

        val details = restored.getSshConnection()!!
        assertEquals("example.com", details.host)
        assertEquals(2222, details.port)
        assertEquals("alice", details.username)
        assertEquals("correct horse", details.password)
        assertEquals("alice@example.com:2222", details.summary())
        assertTrue(restored.hasSshConnection())
        // The previously existing fields are untouched by the new ones.
        assertEquals(publicKey, restored.getSshKeys().publicKey)
        assertEquals(privateKey, restored.getSshKeys().privateKey)
        assertEquals(listOf("ops"), restored.getTags())
    }

    @Test fun connectionFieldsAreOptionalAndLeaveOlderDocumentsByteIdentical() {
        val withoutConnection = entry()
        assertFalse(withoutConnection.toJSON().has("ssh_host"))
        assertFalse(withoutConnection.toJSON().has("ssh_port"))
        assertFalse(withoutConnection.toJSON().has("ssh_username"))
        assertFalse(withoutConnection.toJSON().has("ssh_password"))
        // A document written without the fields parses back to "no connection" rather than blanks.
        val restored = Entry(withoutConnection.toJSON())
        assertFalse(restored.hasSshConnection())
        assertNull(restored.getSshConnection())

        // Partial metadata is allowed: a host without a password is still stored.
        withoutConnection.setSshConnection(SshConnection.of("example.com", 0, "", ""))
        assertTrue(withoutConnection.toJSON().has("ssh_host"))
        assertFalse(withoutConnection.toJSON().has("ssh_port"))
        assertFalse(withoutConnection.toJSON().has("ssh_password"))
        val partial = Entry(withoutConnection.toJSON()).getSshConnection()!!
        assertEquals("example.com", partial.host)
        assertEquals(0, partial.port)
        assertEquals("", partial.password)

        withoutConnection.setSshConnection(null)
        assertFalse(withoutConnection.hasSshConnection())
        assertFalse(withoutConnection.toJSON().has("ssh_host"))
    }

    @Test fun onlySshKeyEntriesCarryConnectionMetadata() {
        val totp = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Issuer", "OTP",
            org.shadowice.flocke.andotp.Utilities.TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        try { totp.setSshConnection(connection()); throw AssertionError("TOTP accepted SSH metadata") }
        catch (_: IllegalArgumentException) { }

        // Changing the type away from SSH_KEY drops the metadata instead of storing it on a TOTP.
        val key = Entry.sshKey(publicKey, privateKey, "", "server", arrayListOf(), connection())
        key.setType(Entry.OTPType.TOTP)
        assertNull(key.getSshConnection())
        assertFalse(key.hasSshConnection())

        // Non-SSH documents that carry the keys anyway are ignored, not applied.
        val json = JSONObject().put("type", "TOTP").put("label", "OTP").put("issuer", "Issuer")
            .put("secret", "JBSWY3DPEHPK3PXP").put("ssh_host", "example.com")
        val parsed = (EntryJson.parse(json) as EntryJsonResult.Success).value
        assertNull(parsed.sshConnection)
        parsed.close()
    }

    @Test fun unusableConnectionDocumentsAreRejected() {
        fun document(host: Any?, port: Any?): JSONObject {
            val json = JSONObject().put("type", "SSH_KEY").put("label", "server")
                .put("secret", Base32().encodeAsString(SshKeyMaterial.encode(publicKey, privateKey)))
            if (host != null) json.put("ssh_host", host)
            if (port != null) json.put("ssh_port", port)
            return json
        }
        assertTrue(EntryJson.parse(document("example.com", 2222)) is EntryJsonResult.Success)
        for (json in listOf(
            document("example.com", 0),                      // a stored port is never 0
            document("example.com", 65536),                  // above the port range
            document("example.com", 22.5),                   // not a whole number
            document("exam ple.com", 22),                    // whitespace in a host name
            document("h".repeat(SshConnection.MAX_HOST_CHARS + 1), 22),
            document("example.com", null)                    // valid: port omitted
        ).dropLast(1)) {
            assertTrue("$json", EntryJson.parse(json) is EntryJsonResult.Failure)
        }
        assertTrue(EntryJson.parse(document("example.com", null)) is EntryJsonResult.Success)
    }

    @Test fun entriesDifferingOnlyByConnectionTargetAreNotDuplicates() {
        val one = Entry.sshKey(publicKey, privateKey, "", "server", arrayListOf(),
            SshConnection.of("one.example.com", 22, "alice", ""))
        val two = Entry.sshKey(publicKey, privateKey, "", "server", arrayListOf(),
            SshConnection.of("two.example.com", 22, "alice", ""))
        val sameTargetDifferentPassword = Entry.sshKey(publicKey, privateKey, "", "server", arrayListOf(),
            SshConnection.of("one.example.com", 22, "alice", "other"))
        assertFalse(one == two)
        assertFalse(one.hashCode() == two.hashCode())
        // The password is not part of the identity, so the same key/target stays one entry.
        assertTrue(one == sameTargetDifferentPassword)
        assertEquals(one.hashCode(), sameTargetDifferentPassword.hashCode())
    }

    @Test fun failedEditRestoresTheConnectionMetadata() {
        val original = Entry.sshKey(publicKey, privateKey, "", "server", arrayListOf(), connection())
        try {
            original.applyEdit {
                it.setSshConnection(SshConnection.of("rejected.example.com", 22, "bob", ""))
                it.setLabel("")
            }
            throw AssertionError("invalid edit accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals("example.com", original.getSshConnection()!!.host)
        assertEquals("alice", original.getSshConnection()!!.username)
    }
}
