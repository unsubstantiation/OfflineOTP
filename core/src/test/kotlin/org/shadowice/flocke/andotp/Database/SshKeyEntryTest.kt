package org.shadowice.flocke.andotp.Database

import org.apache.commons.codec.binary.Base32
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupCodec
import org.shadowice.flocke.andotp.Utilities.SshKeyMaterial

class SshKeyEntryTest {
    private val publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA test@example\n"
    private val privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "X".repeat(4096) +
        "\n-----END OPENSSH PRIVATE KEY-----\n"
    private fun entry(pub: String = publicKey, priv: String = privateKey) =
        Entry.sshKey(pub, priv, "", "server", arrayListOf("work"))

    @Test fun eitherOrBothFieldsRoundTripThroughEncryptedBackup() {
        for ((pub, priv) in listOf(publicKey to "", "" to privateKey, publicKey to privateKey)) {
            val original = entry(pub, priv)
            val json = VaultJson.entriesToString(listOf(original))
            val backup = EncryptedBackupCodec.seal("test-password", json)
            val restored = VaultJson.stringToEntries(EncryptedBackupCodec.open("test-password", backup, false)).single()
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
}
