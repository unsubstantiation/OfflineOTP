package com.fairuniversal.offlineotp.desktop.storage

import com.fairuniversal.offlineotp.Utilities.AutomaticBackupCredentialCodec
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class DesktopAutomaticBackupStoreTest {
    @Test fun snapshotModeIsVaultEncryptedAndDoesNotReinterpretOldExportConfigurations() {
        val root = Files.createTempDirectory("auto-copy-config-").toFile(); val key = SecretKeySpec(ByteArray(32), "AES")
        try {
            val store = DesktopAutomaticBackupStore(root)
            DesktopAutomaticBackupStore.Configuration(root.absolutePath, charArrayOf(), 15, 4, vaultCopy = true).use { store.save(key, it) }
            store.load(key)!!.use { config ->
                assertTrue(config.vaultCopy); assertTrue(config.password.isEmpty()); assertTrue(config.recipientKeys.isEmpty()); assertFalse(config.requiresSigning)
            }
            val document = AutomaticBackupCredentialCodec.open(key, File(root, DesktopAutomaticBackupStore.FILENAME).readBytes())
            val json = org.json.JSONObject(String(document)); assertEquals(3, json.getInt("version")); assertEquals("vault-copy", json.getString("mode")); document.fill(0)
            for (bad in listOf(json.toString().replace("\"password\":\"\"", "\"password\":\"secret\""),
                    org.json.JSONObject(json.toString()).put("requiresSigning", true).toString(),
                    org.json.JSONObject(json.toString()).put("recipientKeys", org.json.JSONArray()).toString())) {
                var rejected = false
                try { AutomaticBackupCredentialCodec.parseConfiguration(bad.toByteArray()).close() } catch (_: Exception) { rejected = true }
                assertTrue(rejected)
            }
            DesktopAutomaticBackupStore.Configuration(root.absolutePath, "old-export-password".toCharArray(), 1, 2).use { store.save(key, it) }
            store.load(key)!!.use { assertFalse(it.vaultCopy); assertEquals("old-export-password", it.password.concatToString()) }
        } finally { root.deleteRecursively() }
    }

    @Test fun remembersOnlyEncryptedCredentialsAndLoadsAfterReconstruction() {
        val root = Files.createTempDirectory("automatic-credentials-").toFile()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        try {
            val store = DesktopAutomaticBackupStore(root)
            DesktopAutomaticBackupStore.Configuration(root.absolutePath, "separate-backup-secret".toCharArray(), 15, 10).use { store.save(key, it) }
            val file = File(root, DesktopAutomaticBackupStore.FILENAME)
            assertFalse(file.readText(Charsets.ISO_8859_1).contains("separate-backup-secret"))
            assertFalse(file.readText(Charsets.ISO_8859_1).contains(root.absolutePath))
            DesktopAutomaticBackupStore(root).load(key)!!.use { loaded ->
                assertEquals(root.absolutePath, loaded.folder); assertEquals(15, loaded.minutes); assertEquals(10, loaded.keep)
                assertEquals("separate-backup-secret", loaded.password.concatToString())
                assertEquals("AutomaticBackupConfiguration(redacted)", loaded.toString())
                loaded.close(); assertTrue(loaded.password.all { it == '\u0000' })
            }
            try { store.load(SecretKeySpec(ByteArray(32) { 8 }, "AES")); fail("wrong vault key accepted") } catch (_: Exception) { }
            assertTrue(file.exists()) // Rekey/recovery failure preserves the old encrypted record, never silently deletes it.
            store.forget(); assertFalse(store.exists); assertTrue(store.load(key) == null)
        } finally { root.deleteRecursively() }
    }
    @Test fun tamperingOversizeAndSymlinksNeverProduceConfiguration() {
        val root = Files.createTempDirectory("automatic-invalid-").toFile()
        val key = SecretKeySpec(ByteArray(16) { 3 }, "AES")
        try {
            val file = File(root, DesktopAutomaticBackupStore.FILENAME); val store = DesktopAutomaticBackupStore(root)
            val plain = AutomaticBackupCredentialCodec.configurationDocument(root.absolutePath, "password".toCharArray(), 1, 2)
            val encrypted = AutomaticBackupCredentialCodec.seal(key, plain)
            for (index in encrypted.indices) {
                file.writeBytes(encrypted.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() })
                try { store.load(key); fail("tampering accepted at $index") } catch (_: Exception) { }
            }
            file.writeBytes(ByteArray(AutomaticBackupCredentialCodec.MAX_PLAINTEXT_BYTES + 33))
            try { store.load(key); fail("oversized record accepted") } catch (_: Exception) { }
            file.delete()
            val elsewhere = File(root, "elsewhere").apply { writeBytes(encrypted) }
            Files.createSymbolicLink(file.toPath(), elsewhere.toPath())
            try { store.load(key); fail("symlink accepted") } catch (_: Exception) { }
            store.forget(); assertTrue(elsewhere.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun encryptedRecipientConfigurationReopensWithoutBorrowingCallerOrLoadedArrays() {
        val root = Files.createTempDirectory("automatic-pgp-config-").toFile()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        try {
            val keys = listOf("synthetic-public-certificate".toByteArray())
            val store = DesktopAutomaticBackupStore(root)
            DesktopAutomaticBackupStore.Configuration(root.absolutePath, charArrayOf(), 15, 3, keys).use { store.save(key, it) }
            assertTrue(keys.single().all { it == 0.toByte() })
            val disk = File(root, DesktopAutomaticBackupStore.FILENAME).readBytes()
            assertFalse(String(disk, Charsets.ISO_8859_1).contains("synthetic-public-certificate"))
            DesktopAutomaticBackupStore(root).load(key)!!.use { loaded ->
                assertTrue(loaded.password.isEmpty()); assertEquals(15, loaded.minutes)
                assertEquals("synthetic-public-certificate", String(loaded.recipientKeys.single()))
            }
            DesktopAutomaticBackupStore(root).load(key)!!.use { loaded ->
                assertEquals("synthetic-public-certificate", String(loaded.recipientKeys.single()))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun signingRequirementPersistsButSessionCredentialsHaveNoConfigurationField() {
        val root = Files.createTempDirectory("automatic-signed-config-").toFile()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        try {
            val store = DesktopAutomaticBackupStore(root)
            DesktopAutomaticBackupStore.Configuration(root.absolutePath, charArrayOf(), 15, 3, listOf(byteArrayOf(1)), true).use { store.save(key, it) }
            store.load(key)!!.use { loaded -> assertTrue(loaded.requiresSigning); assertTrue(loaded.password.isEmpty()) }
            val document = AutomaticBackupCredentialCodec.open(key, File(root, DesktopAutomaticBackupStore.FILENAME).readBytes())
            val text = String(document)
            assertFalse(text.contains("passphrase")); assertFalse(text.contains("signingKey")); assertTrue(text.contains("requiresSigning"))
            document.fill(0)
        } finally { root.deleteRecursively() }
    }

}
