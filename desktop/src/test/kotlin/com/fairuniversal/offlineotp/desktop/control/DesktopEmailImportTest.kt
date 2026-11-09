package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEmailImportTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private fun sample() = EmailAccounts.create("Work", "person@example.org", 0,
        "imap.example.org", 993, 0, 1, "person@example.org", "incoming secret",
        "smtp.example.org", 465, 0, 1, "person@example.org", "outgoing secret")
    private fun harness(test: (File, VaultController, DesktopVaultState) -> Unit) {
        val dir = Files.createTempDirectory("compose-email-import-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("vault-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        try { test(dir, controller, state) } finally { state.lock(); dir.deleteRecursively() }
    }
    @Test fun plaintextGzipAndEncryptedBackupsRequireConfirmationAndPersist() {
        for (format in 0..3) harness { dir, controller, state ->
            val entry = sample()
            val plain = try { EmailAccounts.exportBackup(listOf(entry)) } finally { entry.destroy() }
            val bytes = when (format) {
                0 -> plain.copyOf()
                1 -> ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
                else -> encryptedFairEmailBackup(format - 2, plain, "backup-password")
            }
            plain.fill(0)
            val source = File(dir, "email.backup").apply { writeBytes(bytes) }
            val worker = Queue(); val ui = Queue()
            val importer = DesktopEmailImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries)
            try {
                importer.open(source); val request = importer.pending!!
                importer.submit(request, if (format < 2) "" else "backup-password") { true }
                worker.run(); ui.run()
                assertEquals(1, request.count); assertTrue(state.snapshot().isEmpty())
                assertTrue(importer.confirm(request) { true })
                assertEquals(1, importer.importedCount)
                val reopened = controller.openVault("vault-password") as VaultController.Session.Loaded
                try {
                    val account = reopened.entries.single()
                    assertTrue(account.isEmail())
                    assertTrue(EmailAccounts.details(account, true)!!.any { it.value == "incoming secret" })
                    assertTrue(EmailAccounts.details(account, true)!!.any { it.value == "outgoing secret" })
                } finally { DesktopUnlockOperation.discard(reopened) }
                assertTrue(bytes.contentEquals(source.readBytes()))
            } finally { importer.close(); bytes.fill(0) }
        }
    }
    @Test fun wrongPasswordCanRetryWithoutInstallingAnything() = harness { dir, controller, state ->
        val entry = sample()
        val plain = try { EmailAccounts.exportBackup(listOf(entry)) } finally { entry.destroy() }
        val source = File(dir, "email.backup")
        val encrypted = encryptedFairEmailBackup(1, plain, "correct")
        source.writeBytes(encrypted); plain.fill(0); encrypted.fill(0)
        val worker = Queue(); val ui = Queue()
        val importer = DesktopEmailImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries)
        try {
            importer.open(source); val request = importer.pending!!
            importer.submit(request, "wrong") { true }; worker.run(); ui.run()
            assertTrue(request.failed); assertTrue(state.snapshot().isEmpty())
            importer.submit(request, "correct") { true }; worker.run(); ui.run()
            assertFalse(request.failed); assertEquals(1, request.count)
            assertTrue(importer.confirm(request) { true })
        } finally { importer.close() }
    }
    @Test fun queuedInputIsCancelledAndReadyResultsAreDestroyed() = harness { _, controller, state ->
        val worker = Queue(); val ui = Queue(); var decodes = 0
        val owned = sample()
        val importer = DesktopEmailImport(state, worker, ui::execute, {}, EntryEditor(controller, state)::importEntries,
            decode = { _, password -> assertEquals("secret", password); decodes++; arrayListOf(owned) })
        importer.open(File("unused")); val old = importer.pending!!
        importer.submit(old, "never-read") { true }; importer.dismiss(old); worker.run()
        assertEquals(0, decodes)
        importer.open(File("unused")); val fresh = importer.pending!!
        assertFalse(importer.submit(old, "stale") { true })
        importer.submit(fresh, "secret") { true }; worker.run(); ui.run()
        state.lock(); importer.close()
        assertEquals(0, owned.getSecretSize())
        assertFalse(importer.confirm(fresh) { true })
    }
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

}
