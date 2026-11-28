package com.fairuniversal.offlineotp.Utilities

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.VaultJson
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

    @Test fun expandedLinScpMetadataSurvivesImportVaultReloadAndBothExportModes() {
        // Assemble PWD1 directly from the upstream spec, not our encoder. TLS
        // values reach the upstream maximum: 384 KiB raw = 512 KiB Base64.
        val blob = Base64.getEncoder().encodeToString(ByteArray(384 * 1024) { (it % 251).toByte() })
        val session = """{"name":"TLS session","protocol":"webdavs","host":"dav.example","port":443,"folder":"Work/Servers","local_directory":"/local","remote_directory":"/remote","key_password":"key-passphrase","proxy_password":"proxy-secret","tunnel":{"enabled":true,"host":"jump.example","password":"jump-secret","passphrase":"jump-key-secret"},"extra_fields":{"other_local_dir":"/other","alt_hostname":"dr.example","alt_port":"2223","auth_gssapi":"1","s3_region":"ap-southeast-1","color":"#22aa88","note":"retained","future_option":"opaque"}}"""
        val pairs = listOf("url" to "webdavs://dav.example:443", "username" to "alice", "password" to "server-secret",
            "linscp_session_json" to session, "linscp_ca_cert_b64" to blob,
            "linscp_tls_cert_b64" to blob, "linscp_tls_key_b64" to blob,
            "linscp_cached_hostkeys_b64" to Base64.getEncoder().encodeToString("dav.example:22=pin".toByteArray()),
            "linscp_transfer_preset_b64" to Base64.getEncoder().encodeToString("[Custom]\nmode=binary\n".toByteArray()),
            "linscp_future_blob" to "opaque future data")
        fun document(fields: List<Pair<String, String>>): ByteArray {
            val buffer = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(buffer).use { out ->
                out.writeInt(0x50574431); out.writeInt(fields.size)
                for ((name, value) in fields) for (field in listOf(name, value)) {
                    val bytes = field.toByteArray(StandardCharsets.UTF_8)
                    out.writeInt(bytes.size); out.write(bytes); bytes.fill(0)
                }
            }
            val raw = buffer.toByteArray()
            return try {
                JSONArray().put(JSONObject().put("type", "PASSWORD").put("label", "TLS session")
                    .put("secret", String(org.apache.commons.codec.binary.Base32().encode(raw), StandardCharsets.US_ASCII)))
                    .toString().toByteArray(StandardCharsets.UTF_8)
            } finally { raw.fill(0) }
        }
        fun verify(entries: List<Entry>) {
            assertEquals(1, entries.size)
            val fields = entries.single().getPasswordFields()
            assertEquals(pairs.map { it.first }.toSet(), fields.columns().toSet())
            for ((name, value) in pairs) assertEquals(value, fields.value(name))
        }
        val source = document(pairs)
        val imported = LinScpCredentials.importBytes(source)
        try {
            verify(imported)
            val reloaded = VaultJson.stringToEntries(VaultJson.entriesToString(imported))
            try {
                verify(reloaded)
                for (encrypted in listOf(false, true)) {
                    val exported = if (encrypted) LinScpCredentials.exportVault(reloaded, "metadata-test-password")
                        else LinScpCredentials.exportPlain(reloaded)
                    try {
                        val reopened = LinScpCredentials.importBytes(exported, if (encrypted) "metadata-test-password" else null)
                        try { verify(reopened) } finally { reopened.forEach { it.destroy() } }
                    } finally { exported.fill(0) }
                }
            } finally { reloaded.forEach { it.destroy() } }
        } finally { imported.forEach { it.destroy() }; source.fill(0) }
        val oversized = document(pairs.take(3) + ("linscp_tls_key_b64" to (blob + "A")))
        try {
            try {
                LinScpCredentials.importBytes(oversized).forEach { it.destroy() }
                fail("Expected oversized field rejection")
            } catch (_: IllegalArgumentException) { /* all-or-nothing, never truncate */ }
        } finally { oversized.fill(0) }
    }

    @Test fun newPlainDocumentKeepsAllSessionSettingsAndUsesDistinctFilename() {
        val numericIni = LinScpCredentials.importBytes("[123 server]\n# comment\nhost=server.example\nusername=alice\npassword=secret\n".toByteArray())
        try { assertEquals("123 server", numericIni.single().getLabel()) } finally { numericIni.forEach { it.destroy() } }
        val session = JSONObject().put("name", "WebDAV").put("protocol", "webdavs")
            .put("host", "dav.example").put("port", 443).put("username", "alice")
            .put("password", "server-password").put("key_password", "key-passphrase")
            .put("remote_directory", "/files").put("host_key", "fingerprint")
            .put("proxy_method", "http").put("proxy_password", "proxy-secret")
            .put("tls_verify", true).put("future_setting", JSONObject().put("keep", 42))
            .put("tunnel", JSONObject().put("host", "jump.example").put("port", 22)
                .put("username", "jump-user").put("password", "jump-password")).toString()
        val entry = Entry.passwordFields("WebDAV", "dav.example", listOf("url" to "webdavs://dav.example:443",
            "username" to "alice", "password" to "server-password", "linscp_session_json" to session,
            "linscp_future" to "opaque"))
        try {
            val bytes = LinScpCredentials.exportPlain(listOf(entry))
            assertFalse(LinScpCredentials.isEncryptedVault(bytes))
            assertEquals('[', bytes[0].toInt().toChar())
            val imported = LinScpCredentials.importBytes(bytes)
            try {
                assertEquals(session, imported.single().getPasswordFields().value("linscp_session_json"))
                assertEquals("opaque", imported.single().getPasswordFields().value("linscp_future"))
                val encrypted = LinScpCredentials.exportVault(imported, "password")
                val reopened = LinScpCredentials.importBytes(encrypted, "password")
                try { assertEquals(session, reopened.single().getPasswordFields().value("linscp_session_json")) }
                finally { reopened.forEach { it.destroy() }; encrypted.fill(0) }
            } finally { imported.forEach { it.destroy() }; bytes.fill(0) }
            assertEquals("key_session_1970-01-01_00-00-00.json", LinScpCredentials.exportFilename(0L, true))
            assertEquals("key_session_1970-01-01_00-00-00.json.aes", LinScpCredentials.exportFilename(0L))
            var rejected = false
            try { LinScpCredentials.exportVault(listOf(entry), "") } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            for (invalid in listOf("[]", "[1]", "[{\"type\":\"PASSWORD\",\"label\":\"bad\",\"secret\":\"???\"}]")) {
                var failed = false
                try { LinScpCredentials.importBytes(invalid.toByteArray()).forEach { it.destroy() } } catch (_: Exception) { failed = true }
                assertTrue(failed)
            }
        } finally { entry.destroy() }
    }

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
