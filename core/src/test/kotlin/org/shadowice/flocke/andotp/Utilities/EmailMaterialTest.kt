package org.shadowice.flocke.andotp.Utilities

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryJson
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

    @Test fun accountCredentialsRoundTripThroughEncryptedVaultJson() {
        val source = sample()
        val entry = Entry.email(source.account, source.identity)
        assertTrue(entry.isEmail())
        val document = EntryJson.parse(entry.toJSON()) as org.shadowice.flocke.andotp.Database.EntryJsonResult.Success
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
        for (version in 0..1) {
            val salt = ByteArray(16) { (it + 1).toByte() }
            val iv = ByteArray(if (version == 0) 16 else 12) { (it + 20).toByte() }
            val chars = password.toCharArray()
            val spec = PBEKeySpec(chars, salt, if (version == 0) 65536 else 120000, 256)
            chars.fill('\u0000')
            val digest = SecretKeyFactory.getInstance(if (version == 0) "PBKDF2WithHmacSHA1" else "PBKDF2WithHmacSHA512")
                .generateSecret(spec).encoded
            spec.clearPassword()
            val cipher = Cipher.getInstance(if (version == 0) "AES/CBC/PKCS5Padding" else "AES/GCM/NoPadding")
            val key = SecretKeySpec(digest, "AES")
            if (version == 0) cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
            else cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(plain)
            val backup = ByteArrayOutputStream().apply {
                if (version == 1) { write("___FairEmail___".toByteArray(Charsets.US_ASCII)); write(version) }
                write(salt); write(iv); write(encrypted)
            }.toByteArray()
            val imported = EmailMaterial.importBackup(backup, password).single()
            assertEquals(account.address, imported.address)
            assertEquals("incoming secret", imported.account.getString("password"))
            backup.fill(0); digest.fill(0); encrypted.fill(0)
        }
        plain.fill(0)
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
}
