package org.shadowice.flocke.andotp.Utilities

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.VaultJson
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

class LinScpCredentialsTest {
    private val samplePublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest deploy@prod.example"
    private val samplePrivateKey =
        "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAA=\n-----END OPENSSH PRIVATE KEY-----\n"
    private val sampleTunnelPublic = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBastion ops@bastion.example"
    private val sampleTunnelPrivate =
        "-----BEGIN OPENSSH PRIVATE KEY-----\nYmFzdGlvbi1rZXktdjEAAAA=\n-----END OPENSSH PRIVATE KEY-----\n"

    @Test
    fun lscp1MatchesLinScpRustReferenceVectorByteForByte() {
        val masterKey = ByteArray(32) { it.toByte() }
        val ciphertext =
            "lscp1\$EBESExQVFhcYGRobHB0eH2O4ezzmYH370G39JNEWV3UKmNy5iaxBwNro1exoTvVdvdwtDnFxSYetybMyM3aAIA=="
        assertEquals("S3cr3t!Pass#2026", LinScpCredentials.decryptLscp1Password(ciphertext, masterKey))

        val wrongKey = ByteArray(32) { 0x22 }
        try {
            LinScpCredentials.decryptLscp1Password(ciphertext, wrongKey)
            fail("Expected lscp1 authentication failure with wrong master key")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun lscpvlt2VaultRoundTripsLinkedKeysJumpHostAndStandaloneKeys() {
        val mainKeyLabel = "Prod App [LinSCP SSH key]"
        val jumpKeyLabel = "Prod App [LinSCP jump-host key]"

        val mainKey = Entry.sshKey(
            samplePublicKey,
            samplePrivateKey,
            "",
            mainKeyLabel,
            arrayListOf("ops"),
            SshConnection.of("prod.example", 2222, "deploy", "")
        )
        val jumpKey = Entry.sshKey(
            sampleTunnelPublic,
            sampleTunnelPrivate,
            "",
            jumpKeyLabel,
            arrayListOf(),
            SshConnection.of("bastion.example", 22, "ops", "")
        )
        val standaloneKey = Entry.sshKey(
            samplePublicKey,
            samplePrivateKey,
            "git.example",
            "Git [Work]",
            arrayListOf("dev"),
            SshConnection.of("git.example", 22, "git", "git-pass")
        )

        val sessionJson = JSONObject()
            .put("name", "Prod App")
            .put("protocol", "sftp")
            .put("host", "prod.example")
            .put("port", 2222)
            .put("username", "deploy")
            .put("password", "app-secret-pass")
            .put("remote_directory", "/srv/app")
            .put(
                "tunnel",
                JSONObject()
                    .put("enabled", true)
                    .put("host", "bastion.example")
                    .put("port", 22)
                    .put("username", "ops")
                    .put("password", "jump-secret-pass")
                    .put("hostkey", "")
                    .put("local_port", 0)
            )
            .toString()

        val loginEntry = Entry.passwordFields(
            "Prod App",
            "",
            listOf(
                "url" to "sftp://prod.example:2222",
                "username" to "deploy",
                "password" to "app-secret-pass",
                LinScpCredentials.SESSION_JSON_COLUMN to sessionJson,
                LinScpCredentials.SSH_KEY_LABEL_COLUMN to mainKeyLabel,
                LinScpCredentials.TUNNEL_KEY_LABEL_COLUMN to jumpKeyLabel
            ),
            arrayListOf("ops")
        )
        val totpIgnored = Entry().apply {
            setType(Entry.OTPType.TOTP)
            setSecret("JBSWY3DPEHPK3PXP".toByteArray(StandardCharsets.US_ASCII))
            setIssuer("GitHub", true)
            setLabel("octocat")
        }

        val salt = ByteArray(16) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 0x20).toByte() }
        val sealed = LinScpCredentials.exportVault(
            listOf(mainKey, jumpKey, standaloneKey, loginEntry, totpIgnored),
            "linscp-vault-pass",
            salt,
            nonce
        )
        assertTrue(LinScpCredentials.isEncryptedVault(sealed))
        assertEquals("LSCPVLT2", String(sealed, 0, 8, StandardCharsets.US_ASCII))

        val reopened = LinScpCredentials.openVault(sealed, "linscp-vault-pass")
        assertEquals(4, reopened.size)

        // Linked SSH keys are enriched with their session/jump-host passwords and issuer host!
        val importedMainKey = reopened[0]
        assertTrue(importedMainKey.isSshKey())
        assertEquals(mainKeyLabel, importedMainKey.getLabel())
        assertEquals("prod.example", importedMainKey.getIssuer())
        assertEquals("app-secret-pass", importedMainKey.getSshConnection()?.password)

        val importedJumpKey = reopened[1]
        assertTrue(importedJumpKey.isSshKey())
        assertEquals(jumpKeyLabel, importedJumpKey.getLabel())
        assertEquals("bastion.example", importedJumpKey.getIssuer())
        assertEquals("jump-secret-pass", importedJumpKey.getSshConnection()?.password)

        // Standalone SSH key replaces '[' / ']' with '(' / ')' so LinSCP's validate_imported_site accepts it!
        val importedStandalone = reopened[2]
        assertEquals("Git (Work)", importedStandalone.getLabel())
        assertEquals("git-pass", importedStandalone.getSshConnection()?.password)

        val importedSession = reopened[3]
        assertTrue(importedSession.isPassword())
        assertEquals("Prod App", importedSession.getLabel())
        assertEquals("prod.example", importedSession.getIssuer())
        val details = LinScpCredentials.formatPasswordDetails(importedSession.getPasswordFields())
        assertTrue(details.contains("Jump host: ops@bastion.example:22"))
        assertTrue(details.contains("Remote directory: /srv/app"))

        // Standard EncryptedBackupCodec.open also opens LSCPVLT2 vaults seamlessly!
        val backupJson = EncryptedBackupCodec.open("linscp-vault-pass", sealed)
        val restoredViaBackup = VaultJson.stringToEntries(backupJson)
        assertEquals(4, restoredViaBackup.size)

        // Tampering with the LSCPVLT2 AAD header or ciphertext fails authentication.
        val wrongVersion = sealed.copyOf().also { it[7] = '1'.code.toByte() }
        assertFalse(LinScpCredentials.isEncryptedVault(wrongVersion))
        val tamperedTag = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        try {
            LinScpCredentials.openVault(tamperedTag, "linscp-vault-pass")
            fail("Expected GCM tag failure on tampered vault")
        } catch (_: Exception) {
            // Expected.
        }
    }

    @Test
    fun backupBundleAndSessionsIniDecryptLscp1SecretsAndBuildPortableSessions() {
        val masterKey = ByteArray(32) { it.toByte() }
        val masterKeyB64 = Base64.getEncoder().encodeToString(masterKey)
        val encryptedPass =
            "lscp1\$EBESExQVFhcYGRobHB0eH2O4ezzmYH370G39JNEWV3UKmNy5iaxBwNro1exoTvVdvdwtDnFxSYetybMyM3aAIA=="

        val ini = """
            # LinSCP saved sessions
            [Staging SFTP]
            protocol=sftp
            host=staging.example.org
            port=2200
            username=ubuntu
            password=$encryptedPass
            remote_directory=/var/www
            tunnel=1
            tunnel_host=jump.example.org
            tunnel_port=22
            tunnel_username=bastion
            tunnel_password=$encryptedPass

            [Docs WebDAV]
            protocol=https
            host=dav.example.org
            username=editor
            password=plain-dav-pass
        """.trimIndent()

        val bundleJson = JSONObject()
            .put("version", 1)
            .put("sessions_ini", ini)
            .put("sessions_key_b64", masterKeyB64)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        assertFalse(LinScpCredentials.needsSessionMasterKey(bundleJson))
        val fromBundle = LinScpCredentials.importBytes(bundleJson)
        assertEquals(2, fromBundle.size)

        val sftp = fromBundle[0]
        assertEquals("Staging SFTP", sftp.getLabel())
        assertEquals("staging.example.org", sftp.getIssuer())
        assertEquals("sftp://staging.example.org:2200", sftp.getPasswordFields().value("url"))
        assertEquals("ubuntu", sftp.getPasswordFields().value("username"))
        assertEquals("S3cr3t!Pass#2026", sftp.getPasswordFields().value("password"))
        val storedSite = JSONObject(sftp.getPasswordFields().value(LinScpCredentials.SESSION_JSON_COLUMN))
        assertEquals("/var/www", storedSite.getString("remote_directory"))
        assertEquals("S3cr3t!Pass#2026", storedSite.getJSONObject("tunnel").getString("password"))

        val dav = fromBundle[1]
        assertEquals("davs://dav.example.org:443", dav.getPasswordFields().value("url"))
        assertEquals("plain-dav-pass", dav.getPasswordFields().value("password"))

        // Importing raw sessions.ini directly detects lscp1$ and decrypts with sessions.key!
        val iniBytes = ini.toByteArray(StandardCharsets.UTF_8)
        assertTrue(LinScpCredentials.needsSessionMasterKey(iniBytes))
        val fromIni = LinScpCredentials.importBytes(
            iniBytes,
            masterKeyBytes = masterKeyB64.toByteArray(StandardCharsets.US_ASCII)
        )
        assertEquals(2, fromIni.size)
        assertEquals("S3cr3t!Pass#2026", fromIni[0].getPasswordFields().value("password"))
        assertNotNull(LinScpCredentials.exportVault(fromIni, "reexport-password"))
    }

    @Test
    fun rejectsNonCanonicalBase32InsideLscpvlt2Vault() {
        val badEntry = JSONArray().put(
            JSONObject()
                .put("type", "SSH_KEY")
                .put("label", "Bad Key")
                .put("secret", "not-valid-base32!")
        )
        val salt = ByteArray(16) { 0x44 }
        val nonce = ByteArray(12) { 0x55 }
        val keyBytes = Argon2id.deriveFromPassword("secret-pass", salt, 65_536, 3, 1, 32)
        val ct = CryptoPrimitives.encryptWithAad(
            SecretKeySpec(keyBytes, "AES"),
            nonce,
            badEntry.toString().toByteArray(StandardCharsets.UTF_8),
            "LSCPVLT2".toByteArray(StandardCharsets.US_ASCII)
        )
        val framed = ByteArray(8 + 16 + 12 + ct.size)
        System.arraycopy("LSCPVLT2".toByteArray(StandardCharsets.US_ASCII), 0, framed, 0, 8)
        System.arraycopy(salt, 0, framed, 8, 16)
        System.arraycopy(nonce, 0, framed, 24, 12)
        System.arraycopy(ct, 0, framed, 36, ct.size)

        try {
            LinScpCredentials.openVault(framed, "secret-pass")
            fail("Expected non-canonical Base32 secret to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
