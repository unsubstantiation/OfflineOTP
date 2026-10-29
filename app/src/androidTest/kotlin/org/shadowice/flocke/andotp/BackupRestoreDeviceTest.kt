/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * On-device restore coverage for the two backup reports in issue #39:
 *
 *  - #376 "Cannot restore encrypted backup": the current [iteration][salt][nonce+ciphertext]
 *    frame and the legacy SHA-256-keyed frame are both decoded through the task that the backup
 *    screen runs, with distinct outcomes for a wrong password, a truncated document and a missing
 *    document.
 *  - #976 "Cannot restore Plain-text backup": a legacy unencrypted JSON document is read through
 *    the bounded document reader and the strict UTF-8 decoder, and malformed input is refused.
 *
 * The fixtures are written to the app's private cache directory, which is the only `file:` path
 * StorageAccessHelper accepts; the encryption, bounded reads and UTF-8 handling are the real
 * Android implementations. Writing to a user-selected SAF provider still needs a provider/device
 * check (documented in docs/andotp-carryover-issue-pass-2026-10-19.md).
 */
package org.shadowice.flocke.andotp

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
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult
import org.shadowice.flocke.andotp.Tasks.EncryptedRestoreTask
import org.shadowice.flocke.andotp.Tasks.PlainTextRestoreTask
import org.shadowice.flocke.andotp.Tasks.TaskOutcome
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.VaultJson
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupCodec
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackupRestoreDeviceTest {
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
    fun sharedDesktopInteropVectorsRestoreThroughAndroidTask() {
        val expected = String(golden("expected-vault.json"), StandardCharsets.UTF_8)
        for ((name, legacy) in listOf("otp_accounts.json.aes" to false,
                "desktop-export.json.aes" to false, "legacy-otp_accounts.json.aes" to true)) {
            val file = writeDocument(golden(name))
            try {
                val result = restoreEncrypted(file, "golden-interop-password", legacy)
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
            val result = restoreEncrypted(file, "golden-interop-password", false)
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
            val result = restoreEncrypted(file, "golden-interop-password", false)
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
            val result = restoreEncrypted(file, "golden-interop-password", false)
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
        val decoded = EncryptedBackupCodec.open(PASSWORD, encoded, false)
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
            val result = restoreEncrypted(file, PASSWORD, oldFormat = false)
            assertTrue("the current encrypted backup was not restored", result.success)
            assertEquals(document, result.payload)
            assertEquals(BackupTaskResult.ResultType.RESTORE, result.resultType)
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacyEncryptedBackupIsRestored() {
        val file = writeDocument(legacyEncryptedFrame(PASSWORD, document))
        try {
            val result = restoreEncrypted(file, PASSWORD, oldFormat = true)
            assertTrue("the legacy encrypted backup was not restored", result.success)
            assertEquals(document, result.payload)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aWrongPasswordIsRejected() {
        val file = writeDocument(currentEncryptedFrame(PASSWORD, document))
        try {
            val result = restoreEncrypted(file, "not the password", oldFormat = false)
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
            val result = restoreEncrypted(file, PASSWORD, oldFormat = false)
            assertFalse("a truncated backup was accepted", result.success)
            assertEquals(R.string.backup_toast_import_decryption_failed, result.messageId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aMissingDocumentFailsWithoutCrashing() {
        val missing = File(context.getCacheDir(), "issue-376-missing-${UUID.randomUUID()}.json.aes")
        val result = restoreEncrypted(missing, PASSWORD, oldFormat = false)
        assertFalse("a missing document reported success", result.success)
        assertEquals(R.string.backup_toast_import_decryption_failed, result.messageId)
    }

    @Test
    fun aLegacyPlaintextBackupIsRestored() {
        val file = writeDocument(document.toByteArray(StandardCharsets.UTF_8))
        try {
            val outcome = runTask(PlainTextRestoreTask(context, Uri.fromFile(file)))
            val result = valueOf(outcome, "plain-text restore")
            assertTrue("the plain-text backup was not restored", result.success)
            assertEquals(document, result.payload)
        } finally {
            file.delete()
        }
    }

    @Test
    fun malformedPlaintextIsRejected() {
        val file = writeDocument(byteArrayOf('['.code.toByte(), 0xC3.toByte(), 0x28))
        try {
            val outcome = runTask(PlainTextRestoreTask(context, Uri.fromFile(file)))
            val result = valueOf(outcome, "malformed plain-text restore")
            assertFalse("malformed plain text was accepted", result.success)
            assertEquals(R.string.backup_toast_plaintext_invalid, result.messageId)
        } finally {
            file.delete()
        }
    }

    private fun restoreEncrypted(file: File, password: String, oldFormat: Boolean): BackupTaskResult {
        val outcome = runTask(EncryptedRestoreTask(context, Uri.fromFile(file), password, oldFormat))
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

    private fun legacyEncryptedFrame(password: String, payload: String): ByteArray {
        val key = EncryptionHelper.generateSymmetricKeyFromPassword(password)
        return EncryptionHelper.encrypt(key, payload.toByteArray(StandardCharsets.UTF_8))
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
