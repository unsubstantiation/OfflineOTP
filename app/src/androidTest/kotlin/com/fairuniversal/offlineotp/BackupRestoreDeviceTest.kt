/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * On-device restore coverage for the two backup reports in issue #39:
 *
 *  - #376 "Cannot restore encrypted backup": the current [iteration][salt][nonce+ciphertext]
 *    frame is decoded through the task that the backup screen runs; wrong passwords, truncated
 *    documents, missing documents, and pre-0.6.3 headerless backups are rejected.
  *
 * The fixtures are written to the app's private cache directory, which is the only `file:` path
 * StorageAccessHelper accepts; the encryption, bounded reads and UTF-8 handling are the real
 * Android implementations. Writing to a user-selected SAF provider still needs a provider/device
 * check (documented in docs/andotp-carryover-issue-pass-2026-10-19.md).
 */
package com.fairuniversal.offlineotp

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import com.fairuniversal.offlineotp.Tasks.BackupTaskResult
import com.fairuniversal.offlineotp.Tasks.EncryptedRestoreTask
import com.fairuniversal.offlineotp.Tasks.TaskOutcome
import com.fairuniversal.offlineotp.Tasks.UiBasedBackgroundTask
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupCodec
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupHeader
import com.fairuniversal.offlineotp.Utilities.EncryptionHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.IvParameterSpec

@RunWith(AndroidJUnit4::class)
class BackupRestoreDeviceTest {
    @Test fun portableRestoreWorkflowRequiresPreviewConsentAndLiveAuthorityOnAndroid() {
        val flow = com.fairuniversal.offlineotp.domain.RestoreWorkflow()
        val old = flow.start(1, false); val ticket = flow.start(2, false)
        assertFalse(flow.decoded(old, 1, true, 1, true))
        assertFalse(flow.confirm(ticket, 2, true, true))
        assertTrue(flow.decoded(ticket, 2, true, 1, true))
        assertFalse(flow.beginInstall(ticket, 2, true, 1))
        assertTrue(flow.confirm(ticket, 2, true, false))
        assertFalse(flow.beginInstall(ticket, 3, true, 1))
        assertEquals(com.fairuniversal.offlineotp.domain.RestoreFailure.STALE, flow.failure)
        val plan = com.fairuniversal.offlineotp.domain.RestoreMergePolicy().plan(listOf(true, false), 1, true, true, false)
        assertEquals(listOf(1), plan.importedIndices)
        assertEquals(2, plan.resultCount)
    }

    @Test fun portableVaultProtocolCorpusAndCryptoProvidersAgreeOnAndroid() {
        val policy = com.fairuniversal.offlineotp.domain.VaultProtocol()
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val root = javaClass.classLoader!!.getResourceAsStream("golden/portable-vault-protocol.json")!!.bufferedReader().use { org.json.JSONObject(it.readText()) }
        val rows = root.getJSONArray("headers")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val prefix = hex(row.getString("prefixHex"))
            val result = if (row.getString("family") == "vault") policy.vaultHeader(prefix, row.getInt("totalBytes")) else policy.backupHeader(prefix, row.getInt("totalBytes"))
            assertEquals(row.getString("name"), row.optString("error", null), result.failure?.name)
            assertEquals(row.optString("kdf", null), result.header?.kdf?.name)
        }
        val vectors = javaClass.classLoader!!.getResourceAsStream("golden/vault-container.json")!!.bufferedReader().use { org.json.JSONObject(it.readText()).getJSONArray("vectors") }
        for (i in 0 until vectors.length()) {
            val row = vectors.getJSONObject(i); val file = hex(row.getString("fileHex"))
            assertEquals(row.getString("plaintext"), String(com.fairuniversal.offlineotp.Utilities.VaultContainer.open(row.getString("password"), file), StandardCharsets.UTF_8))
            assertEquals(row.getString("plaintext"), EncryptedBackupCodec.openSelected(row.getString("password"), file, "vault-copy"))
            val tampered = file.copyOf(); tampered[24] = (tampered[24].toInt() xor 1).toByte()
            try { com.fairuniversal.offlineotp.Utilities.VaultContainer.openWithKey(javax.crypto.spec.SecretKeySpec(hex(row.getString("keyHex")), "AES"), tampered); fail("Unauthenticated header") }
            catch (_: Exception) { }
        }
    }

    private val document = """[{"secret":"JBSWY3DPEHPK3PXP","issuer":"Example","label":"alice",""" +
        """"digits":6,"type":"TOTP","algorithm":"SHA1","thumbnail":"Default","last_used":0,""" +
        """"used_frequency":0,"period":30,"tags":["work"]}]"""

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Tasks bind to the session generation they were constructed with. A leftover activity from a
     * previous test could still hold an inactivity callback that revokes the session, which would
     * cancel the restore and look like a restore failure; settle the app first.
     */
    @Before
    fun settleTheApp() {
        DeviceTestSupport.finishActivities()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun portableEntryDocumentsPreserveEveryKindAndFutureNumbersOnAndroid() {
        val rows = org.json.JSONObject(String(golden("portable-entry-documents.json"), StandardCharsets.UTF_8)).getJSONArray("valid")
        assertEquals(9, rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val entries = VaultJson.stringToEntries(row.getString("document"))
            val copy = entries.single().detachedCopy()
            try {
                val restored = VaultJson.stringToEntries(VaultJson.entriesToString(listOf(copy)))
                try {
                    assertEquals(row.getString("kind"), restored.single().getType().name)
                    assertEquals(copy.getLastUsed(), restored.single().getLastUsed())
                    assertEquals(copy.toJSON().get("extension_object").toString(), restored.single().toJSON().get("extension_object").toString())
                } finally { restored.forEach { it.destroy() } }
            } finally { copy.destroy(); entries.forEach { it.destroy() } }
        }
        val text = """[{"secret":"JBSWY3DPEHPK3PXP","label":"synthetic","future":{"number":123456789012345678901234567890,"fraction":1.2300e-15,"exponent":1e9999}}]"""
        val entries = VaultJson.stringToEntries(text)
        val copy = entries.single().detachedCopy()
        try {
            copy.setLabel("edited")
            val saved = VaultJson.entriesToString(listOf(copy))
            assertTrue(saved.contains("123456789012345678901234567890"))
            assertTrue(saved.contains("1.2300e-15"))
            assertTrue(saved.contains("1e9999"))
        } finally { copy.destroy(); entries.forEach { it.destroy() } }
    }

    @Test
    fun portableVaultFileVectorsRestoreThroughTheSameAndroidTask() {
        val root = org.json.JSONObject(String(golden("vault-container.json"), StandardCharsets.UTF_8))
        val vectors = root.getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val row = vectors.getJSONObject(i)
            val bytes = row.getString("fileHex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val file = writeDocument(bytes)
            try {
                val restored = restoreEncrypted(file, row.getString("password"))
                assertTrue("portable vault did not restore", restored.success)
                assertEquals(row.getString("plaintext"), restored.payload)
                assertFalse(restoreEncrypted(file, "wrong password").success)
            } finally { file.delete(); bytes.fill(0) }
        }
    }

    @Test
    fun sharedDesktopInteropVectorsRestoreThroughAndroidTask() {
        val expected = String(golden("expected-vault.json"), StandardCharsets.UTF_8)
        for (name in listOf("otp_accounts.json.aes", "desktop-export.json.aes")) {
            val file = writeDocument(golden(name))
            try {
                val result = restoreEncrypted(file, "golden-interop-password")
                assertTrue("shared backup did not restore: $name", result.success)
                assertEquals(expected, result.payload)
                val entries = VaultJson.stringToEntries(result.payload)
                assertEquals(2, entries.size)
                assertEquals("Golden Issuer", entries[0].getIssuer())
                assertEquals(9L, entries[1].getCounter())
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun desktopPrivacyExportRestoresChoicesThroughAndroidTask() {
        val file = writeDocument(golden("desktop-privacy-export.json.aes"))
        try {
            val result = restoreEncrypted(file, "golden-interop-password")
            assertTrue(result.success)
            val entries = VaultJson.stringToEntries(result.payload)
            assertEquals(listOf(true, false, false, true), entries.map { it.areDetailsVisible() })
            assertEquals(listOf(Entry.OTPType.TOTP, Entry.OTPType.HOTP, Entry.OTPType.NOTE, Entry.OTPType.SSH_KEY),
                entries.map { it.getType() })
        } finally { file.delete() }
    }

    @Test
    fun desktopSshExportRestoresThroughAndroidTask() {
        val file = writeDocument(golden("desktop-ssh-export.json.aes"))
        try {
            val result = restoreEncrypted(file, "golden-interop-password")
            assertTrue(result.success)
            val entries = VaultJson.stringToEntries(result.payload)
            assertEquals(3, entries.size)
            val keys = entries[2].getSshKeys()
            assertEquals("ssh-ed25519 example test@example\n", keys.publicKey)
            assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\n" + "X".repeat(5000) +
                "\n-----END OPENSSH PRIVATE KEY-----\n", keys.privateKey)
            assertEquals("SSH server", entries[2].getLabel())
        } finally {
            file.delete()
        }
    }

    @Test
    fun desktopNoteExportRestoresThroughAndroidTask() {
        val file = writeDocument(golden("desktop-note-export.json.aes"))
        try {
            val result = restoreEncrypted(file, "golden-interop-password")
            assertTrue(result.success)
            val entries = VaultJson.stringToEntries(result.payload)
            assertEquals(3, entries.size)
            assertEquals("Recovery", entries[2].getLabel())
            assertEquals("recovery key\nspaces 🔐", entries[2].getNote())
            assertEquals(listOf("private"), entries[2].getTags())
        } finally {
            file.delete()
        }
    }

    @Test
    fun androidCryptoProviderProducesAFileReadableByTheSharedDesktopCodec() {
        val expected = String(golden("expected-vault.json"), StandardCharsets.UTF_8)
        // Android BackupHelper and the desktop coordinator both call this codec. This checks the
        // Android crypto provider, not SAF writes or an actual desktop process.
        val encoded = EncryptedBackupCodec.seal(PASSWORD, expected)
        val decoded = EncryptedBackupCodec.open(PASSWORD, encoded)
        assertEquals(expected, decoded)
        val entries = VaultJson.stringToEntries(decoded)
        assertEquals(2, entries.size)
        assertEquals("Counter Service", entries[1].getIssuer())
        assertEquals(9L, entries[1].getCounter())
    }

    private fun golden(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/golden/$name")) { "Missing interop fixture: $name" }
            .use { it.readBytes() }

    @Test
    fun currentEncryptedBackupIsRestored() {
        val file = writeDocument(currentEncryptedFrame(PASSWORD, document))
        try {
            val result = restoreEncrypted(file, PASSWORD)
            assertTrue("the current encrypted backup was not restored", result.success)
            assertEquals(document, result.payload)
            assertEquals(BackupTaskResult.ResultType.RESTORE, result.resultType)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aHeaderlessPre063EncryptedBackupIsRejected() {
        // A valid old SHA-256/AES-GCM frame with a nonce that also looks like a current header.
        val nonce = ByteBuffer.allocate(12).putInt(1_000).putLong(0x1020304050607080L).array()
        val key = EncryptionHelper.generateSymmetricKey(MessageDigest.getInstance("SHA-256")
            .digest(PASSWORD.toByteArray(StandardCharsets.UTF_8)))
        val headerless = nonce + EncryptionHelper.encrypt(key, IvParameterSpec(nonce),
            document.toByteArray(StandardCharsets.UTF_8))
        assertEquals(1_000, EncryptedBackupHeader.parse(headerless).getIterations())
        val file = writeDocument(headerless)
        try {
            val result = restoreEncrypted(file, PASSWORD)
            assertFalse("a headerless backup was accepted", result.success)
            assertTrue(result.payload == null)
            assertEquals(R.string.backup_toast_import_decryption_failed, result.messageId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aWrongPasswordIsRejected() {
        val file = writeDocument(currentEncryptedFrame(PASSWORD, document))
        try {
            val result = restoreEncrypted(file, "not the password")
            assertFalse("a wrong password decrypted the backup", result.success)
            assertEquals(R.string.backup_toast_import_decryption_failed, result.messageId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aTruncatedEncryptedBackupIsRejected() {
        val frame = currentEncryptedFrame(PASSWORD, document)
        val file = writeDocument(frame.copyOf(8))
        try {
            val result = restoreEncrypted(file, PASSWORD)
            assertFalse("a truncated backup was accepted", result.success)
            assertEquals(R.string.backup_toast_import_decryption_failed, result.messageId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aMissingDocumentFailsWithoutCrashing() {
        val missing = File(context.getCacheDir(), "issue-376-missing-${UUID.randomUUID()}.json.aes")
        val result = restoreEncrypted(missing, PASSWORD)
        assertFalse("a missing document reported success", result.success)
        assertEquals(R.string.backup_toast_import_decryption_failed, result.messageId)
    }

    private fun restoreEncrypted(file: File, password: String): BackupTaskResult {
        val outcome = runTask(EncryptedRestoreTask(context, Uri.fromFile(file), password))
        return valueOf(outcome, "encrypted restore")
    }

    private fun currentEncryptedFrame(password: String, payload: String): ByteArray {
        val iterations = 1_000
        val salt = EncryptionHelper.generateRandom(Constants.ENCRYPTION_IV_LENGTH)
        val key = EncryptionHelper.generateSymmetricKeyPBKDF2(password, iterations, salt)
        val encrypted = EncryptionHelper.encrypt(key, payload.toByteArray(StandardCharsets.UTF_8))
        val data = ByteArray(Constants.INT_LENGTH + salt.size + encrypted.size)
        ByteBuffer.wrap(data, 0, Constants.INT_LENGTH).putInt(iterations)
        System.arraycopy(salt, 0, data, Constants.INT_LENGTH, salt.size)
        System.arraycopy(encrypted, 0, data, Constants.INT_LENGTH + salt.size, encrypted.size)
        return data
    }

    private fun writeDocument(bytes: ByteArray): File {
        val file = File(context.getCacheDir(), "issue39-${UUID.randomUUID()}.json")
        file.writeBytes(bytes)
        return file
    }

    private fun <T> runTask(task: UiBasedBackgroundTask<T>): TaskOutcome<T> {
        val finished = CountDownLatch(1)
        var outcome: TaskOutcome<T>? = null
        task.setOutcomeCallback {
            outcome = it
            finished.countDown()
        }
        task.execute()
        if (!finished.await(30, TimeUnit.SECONDS)) fail("the restore task did not finish")
        return requireNotNull(outcome) { "the restore task delivered no outcome" }
    }

    private fun valueOf(outcome: TaskOutcome<BackupTaskResult>, what: String): BackupTaskResult =
        when (outcome) {
            is TaskOutcome.Value -> {
                @Suppress("UNCHECKED_CAST")
                (outcome as TaskOutcome.Value<BackupTaskResult>).value
            }
            is TaskOutcome.Failure -> {
                fail("$what failed with ${outcome.error}")
                throw AssertionError("$what failed")
            }
            is TaskOutcome.Cancelled -> {
                fail("$what was cancelled with ${outcome.reason}")
                throw AssertionError("$what was cancelled")
            }
        }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
    }
}
