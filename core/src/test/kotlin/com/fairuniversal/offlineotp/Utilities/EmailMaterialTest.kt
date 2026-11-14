package com.fairuniversal.offlineotp.Utilities

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryJson
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.PBEKeySpec

class EmailMaterialTest {
    private fun sample() = EmailMaterial.create(
        "Work", "person@example.org", "imap.example.org", 993, 0, 0,
        "person@example.org", "incoming secret", 0,
        "smtp.example.org", 465, 0, "person@example.org", "outgoing secret", 0)

    @Test fun detailsIdentifyEverySenderWithoutExposingCredentialsOrMutatingRecords() {
        val source = sample()
        val alias = JSONObject(source.identity.toString()).put("name", "Alternate")
            .put("email", "alias@example.org").put("primary", false).put("password", "alias-token")
        val account = EmailMaterial.Account(source.account, listOf(source.identity, alias))
        val before = account.identities.map { it.toString() }
        val masked = EmailMaterial.details(account, false)
        fun value(label: String) = masked.single { it.label == label }.value
        assertEquals("Work", value("Outgoing (SMTP) sender name"))
        assertEquals("person@example.org", value("Outgoing (SMTP) sender address"))
        assertEquals("Yes", value("Outgoing (SMTP) primary identity"))
        assertEquals("Alternate", value("Outgoing (SMTP) #2 sender name"))
        assertEquals("alias@example.org", value("Outgoing (SMTP) #2 sender address"))
        assertEquals("No", value("Outgoing (SMTP) #2 primary identity"))
        assertEquals(3, masked.count { it.secret })
        assertTrue(masked.filter { it.secret }.all { it.value == EmailMaterial.SECRET_MASK })
        assertTrue(masked.none { it.value in listOf("incoming secret", "outgoing secret", "alias-token") })
        val revealed = EmailMaterial.details(account, true)
        assertEquals(masked.filter { !it.secret }, revealed.filter { !it.secret })
        assertEquals("alias-token", revealed.single { it.label == "Outgoing (SMTP) #2 password" }.value)
        assertEquals(before, account.identities.map { it.toString() })
    }

    @Test fun accountCredentialsRoundTripThroughEncryptedVaultJson() {
        val source = sample()
        val entry = Entry.email(source.account, source.identity)
        assertTrue(entry.isEmail())
        val document = EntryJson.parse(entry.toJSON()) as com.fairuniversal.offlineotp.Database.EntryJsonResult.Success
        val restored = Entry(entry.toJSON())
        try {
            val account = restored.getEmailAccount()
            assertEquals("person@example.org", account.address)
            assertEquals("incoming secret", account.account.getString("password"))
            assertEquals("smtp.example.org", account.identity.getString("host"))
            assertEquals("outgoing secret", account.identity.getString("password"))
        } finally { restored.destroy(); entry.destroy() }
    }

    @Test fun fairEmailPlainFileAndAgentGzipBackupsImportWithEveryIdentity() {
        val source = sample()
        val alternate = JSONObject(source.identity.toString())
            .put("id", 2).put("name", "Alternate")
            .put("email", "alias@example.org").put("primary", false)
            .put("host", "smtp2.example.org").put("password", "alternate secret")
        val original = EmailMaterial.Account(source.account, listOf(source.identity, alternate))
        val plain = EmailMaterial.exportBackup(listOf(original))
        val fromPlain = EmailMaterial.importBackup(plain).single()
        assertEquals(2, fromPlain.identities.size)
        assertEquals("alternate secret", fromPlain.identities[1].getString("password"))

        val root = JSONObject().put("version", 1).put("settings", JSONObject())
        val rows = JSONArray().put(JSONObject(source.account.toString())
            .put("folders", JSONArray()).put("identities", JSONArray().put(source.identity).put(alternate)))
        root.put("accounts", rows)
        val compressed = ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val fromGzip = EmailMaterial.importBackup(compressed).single()
        assertEquals(2, fromGzip.identities.size)
        assertEquals("alias@example.org", fromGzip.identities[1].getString("email"))
        plain.fill(0); compressed.fill(0)
    }

    @Test fun encryptedFairEmailV0AndV1ExportsCanBeImported() {
        val account = sample()
        val plain = EmailMaterial.exportBackup(listOf(account))
        val password = "backup passphrase"
        try {
            for (version in 0..1) {
                val backup = encryptedFairEmailBackup(version, plain, password)
                try {
                    val imported = EmailMaterial.importBackup(backup, password).single()
                    assertEquals(account.address, imported.address)
                    assertEquals("incoming secret", imported.account.getString("password"))
                    assertEquals("outgoing secret", imported.identity.getString("password"))
                } finally { backup.fill(0) }
            }
        } finally { plain.fill(0) }
    }

    /**
     * Independent known-answer fixture for FairEmail's still-current headerless v0 writer:
     * PBKDF2-HMAC-SHA1 (65,536 iterations, 256-bit key), AES-256-CBC/PKCS#5, 16-byte salt and IV.
     * Generated with Python hashlib.pbkdf2_hmac plus OpenSSL, not with EmailMaterial's reader.
     */
    @Test fun fairEmailV0UpstreamWireFormatStillImports() {
        val backup = hexBytes(
            "0102030405060708090a0b0c0d0e0f101415161718191a1b1c1d1e1f20212223" +
            "3f114ef2e821e8ca9aa1ce745d2ca0eaa88065b8d5170082935e1a851fb01222" +
            "709da285b89ea8cf1bc2da6772db10559845942e6014427c63c9140bc7bebf915" +
            "998f394917038c579f231dcf95ee8a394fcd10016e29408c23e17be4d141d2345" +
            "f8dd8e4acf1db8ea1065e61b0adb053dab65ca7e5adc4c41c6cc0f936cdd69c955" +
            "ae4b992576ff08bf3f0b5a471803e31ff5f325129c390957dfe159cf7f3eb591e3" +
            "b7a3a1c5065eb3b4d9e8e5dc961e1226562b79872651b3c5b0a2f4502d2546599a" +
            "681a45cce3266eef601bb7fe7ca32cce145a2510b8b8ff6e353cf4b0ab05f31716" +
            "f5d485844338b525756ef272a31cab0c3d010af20bc937aba713af94f1b7bc33fa" +
            "6aed7f3a4eaa32eacc30cf455763b0524e12014d05bdd3fa70b362480f4a6a6d8f" +
            "e6d812175cbe1d83cb58c29c13280d46a2b93bec533bc52f1ccbb69ffaed621a0b" +
            "211bd71eabec96ed562bdbb81b5aa20510e02249db763226edf836949e09f55111" +
            "a654f30a362318")
        try {
            val imported = EmailMaterial.importBackup(backup, "FairEmail compatibility test").single()
            assertEquals("person@example.org", imported.address)
            assertEquals("Work", imported.name)
            assertEquals("incoming secret", imported.account.getString("password"))
            assertEquals("outgoing secret", imported.identity.getString("password"))
            assertEquals(993, imported.account.getInt("port"))
            assertEquals(465, imported.identity.getInt("port"))
        } finally { backup.fill(0) }
    }

    @Test fun fairEmailV1RejectsWrongPasswordTamperingAndUnsupportedFraming() {
        val plain = EmailMaterial.exportBackup(listOf(sample()))
        val password = "backup passphrase"
        val backup = encryptedFairEmailBackup(1, plain, password)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                EmailMaterial.importBackup(backup, "$password-wrong")
            }
            val tamperedTag = backup.copyOf()
            tamperedTag[tamperedTag.lastIndex] = (tamperedTag[tamperedTag.lastIndex].toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) {
                EmailMaterial.importBackup(tamperedTag, password)
            }
            val tamperedCiphertext = backup.copyOf()
            val ciphertextStart = 16 + 16 + 12
            tamperedCiphertext[ciphertextStart] = (tamperedCiphertext[ciphertextStart].toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) {
                EmailMaterial.importBackup(tamperedCiphertext, password)
            }
            val unsupportedVersion = backup.copyOf()
            unsupportedVersion[15] = 2
            assertThrows(IllegalArgumentException::class.java) {
                EmailMaterial.importBackup(unsupportedVersion, password)
            }
            assertThrows(IllegalArgumentException::class.java) {
                EmailMaterial.importBackup(backup.copyOfRange(0, ciphertextStart + 15), password)
            }
            assertThrows(IllegalArgumentException::class.java) {
                EmailMaterial.importBackup(backup, null)
            }
            tamperedTag.fill(0); tamperedCiphertext.fill(0); unsupportedVersion.fill(0)
        } finally { plain.fill(0); backup.fill(0) }
    }

    @Test fun connectionDetailsMaskPasswordsAndNameTheProtocols() {
        val account = sample()
        val masked = EmailMaterial.details(account, revealSecrets = false)
        val revealed = EmailMaterial.details(account, revealSecrets = true)
        assertEquals(revealed.map { it.label }, masked.map { it.label })
        fun value(rows: List<EmailMaterial.Detail>, label: String) = rows.single { it.label == label }.value
        assertEquals("Work", value(masked, "Account"))
        assertEquals("person@example.org", value(masked, "Address"))
        assertEquals("imap.example.org", value(masked, "Incoming (IMAP) server"))
        assertEquals("993", value(masked, "Incoming (IMAP) port"))
        assertEquals("SSL", value(masked, "Incoming (IMAP) encryption"))
        assertEquals("smtp.example.org", value(masked, "Outgoing (SMTP) server"))
        // Both clients render these rows, so a masked row must never carry the real password.
        assertEquals(EmailMaterial.SECRET_MASK, value(masked, "Incoming (IMAP) password"))
        assertEquals(EmailMaterial.SECRET_MASK, value(masked, "Outgoing (SMTP) password"))
        assertTrue(masked.filter { it.secret }.all { !it.value.contains("secret") })
        assertEquals("incoming secret", value(revealed, "Incoming (IMAP) password"))
        assertEquals("outgoing secret", value(revealed, "Outgoing (SMTP) password"))

        // A POP account is labelled as such, and a blank password shows nothing to mask.
        val pop = EmailMaterial.Account(
            JSONObject(account.account.toString()).put("protocol", 1)
                .put("port", 110).put("encryption", 2).put("password", ""),
            account.identities)
        val popRows = EmailMaterial.details(pop, revealSecrets = false)
        assertTrue(popRows.any { it.label == "Incoming (POP) server" })
        assertEquals("None", value(popRows, "Incoming (POP) encryption"))
        assertEquals("", value(popRows, "Incoming (POP) password"))
        assertEquals("110", value(popRows, "Incoming (POP) port"))
    }

    @Test fun malformedConnectionAndMissingOutgoingIdentityAreRejected() {
        val source = sample()
        val invalid = JSONObject(source.account.toString()).put("port", 70000)
        try {
            EmailMaterial.encode(invalid, listOf(source.identity))
            throw AssertionError("invalid port accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            EmailMaterial.importBackup(JSONObject().put("accounts", JSONArray().put(
                JSONObject(source.account.toString()).put("identities", JSONArray()))).toString().toByteArray())
            throw AssertionError("missing SMTP identity accepted")
        } catch (_: IllegalArgumentException) { }
    }

    /** Test-only writer for the two FairEmail password-protected wire formats. */
    private fun encryptedFairEmailBackup(version: Int, plain: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(if (version == 0) 16 else 12) { (it + 20).toByte() }
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, if (version == 0) 65536 else 120000, 256)
        chars.fill('\u0000')
        var digest: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            val keyBytes = SecretKeyFactory.getInstance(if (version == 0) "PBKDF2WithHmacSHA1" else "PBKDF2WithHmacSHA512")
                .generateSecret(spec).encoded
            digest = keyBytes
            val cipher = Cipher.getInstance(if (version == 0) "AES/CBC/PKCS5Padding" else "AES/GCM/NoPadding")
            val key = SecretKeySpec(keyBytes, "AES")
            if (version == 0) cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
            else cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plain)
            encrypted = ciphertext
            return ByteArrayOutputStream().apply {
                if (version == 1) { write("___FairEmail___".toByteArray(Charsets.US_ASCII)); write(version) }
                write(salt); write(iv); write(ciphertext)
            }.toByteArray()
        } finally {
            spec.clearPassword()
            digest?.fill(0); encrypted?.fill(0); salt.fill(0); iv.fill(0)
        }
    }

    private fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { index ->
            ((Character.digit(hex[index * 2], 16) shl 4) or Character.digit(hex[index * 2 + 1], 16)).toByte()
        }
    }
}
