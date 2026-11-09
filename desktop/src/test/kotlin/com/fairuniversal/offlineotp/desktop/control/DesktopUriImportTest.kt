package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUriImportTest {
    private fun uri(label: String) = "otpauth://totp/Example:$label?secret=JBSWY3DPEHPK3PXP&issuer=Example"
    private fun harness(test: (VaultController, DesktopVaultState, DesktopUriImport, java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("compose-uri-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault("test-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val importer = DesktopUriImport(state, BackupCoordinator(controller, state))
        try { test(controller, state, importer, dir) }
        finally { importer.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun importsPersistWithExplicitDuplicateIssuerPolicy() = harness { controller, state, importer, _ ->
        importer.open(); val request = importer.pending!!
        assertTrue(importer.submit(request, uri("alice"), false) { true })
        assertFalse(importer.submit(request, uri("bob"), false) { true })
        assertEquals(1, state.snapshot().size)
        assertTrue(request.message!!.contains("issuer already exists"))
        assertTrue(importer.submit(request, uri("bob"), true) { true })
        assertFalse(importer.submit(request, uri("bob"), true) { true })
        assertEquals(2, state.snapshot().size)
        val reopened = controller.openVault("test-password") as VaultController.Session.Loaded
        try { assertEquals(listOf("alice", "bob"), reopened.entries.map { it.getLabel() }) }
        finally { DesktopUnlockOperation.discard(reopened) }
        assertFalse(request.message!!.contains("JBSWY3DPEHPK3PXP"))
    }
    @Test fun staleExpiredDismissedAndLockedRequestsCannotImport() = harness { _, state, importer, _ ->
        importer.open(); val old = importer.pending!!
        importer.dismiss(old); importer.open(); val current = importer.pending!!
        assertFalse(importer.submit(old, uri("old"), true) { true })
        assertFalse(importer.submit(current, uri("expired"), true) { false })
        importer.dismiss(old)
        assertTrue(importer.pending === current)
        state.lock()
        assertFalse(importer.submit(current, uri("locked"), true) { true })
        importer.close(); importer.open()
        assertTrue(importer.pending == null)
        assertTrue(state.snapshot().isEmpty())
    }
    @Test fun invalidAndOversizedPayloadsDoNotEchoInputOrChangeVault() = harness { _, state, importer, _ ->
        importer.open(); val request = importer.pending!!
        for (value in listOf("private-invalid-value", " ", "x".repeat(InputPolicy.MAX_URI_CHARS + 1), "otpauth://totp/test?secret=not-valid!")) {
            assertFalse(importer.submit(request, value, false) { true })
            assertFalse(request.message!!.contains("private-invalid-value"))
            assertFalse(request.message!!.contains("not-valid!"))
            assertTrue(state.snapshot().isEmpty())
        }
    }
    @Test fun multipartImportBuffersRejectsOtherPayloadsAndCancelsWithoutSaving() = harness { _, state, importer, _ ->
        importer.open(); val request = importer.pending!!
        assertFalse(importer.submit(request, migrationUri(0, "first"), true) { true })
        assertTrue(request.collecting)
        assertTrue(state.snapshot().isEmpty())
        assertFalse(importer.submit(request, uri("stray"), true) { true })
        assertFalse(importer.submit(request, migrationUri(0, "first"), true) { true })
        importer.dismiss(request); importer.open(); val fresh = importer.pending!!
        assertFalse(fresh.collecting)
        assertFalse(importer.submit(fresh, migrationUri(1, "second"), true) { true })
        assertTrue(state.snapshot().isEmpty()) // Canceled part zero was not reused.
        // Changing the caller flag midway must not change the batch's original allow policy.
        assertTrue(importer.submit(fresh, migrationUri(0, "first"), false) { true })
        assertEquals(2, state.snapshot().size)
        assertFalse(fresh.collecting)
    }
    @Test fun failedPersistenceRollsBackAndReportsFailure() = harness { _, state, importer, dir ->
        importer.open(); val request = importer.pending!!
        dir.deleteRecursively(); dir.writeText("not a directory")
        assertFalse(importer.submit(request, uri("alice"), true) { true })
        assertTrue(state.snapshot().isEmpty())
        assertTrue(request.message!!.contains("Could not save"))
    }
    private fun migrationUri(partIndex: Int, label: String): String {
        val secret = byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x21, partIndex.toByte(), 1, 2, 3)
        val account = fieldBytes(1, secret) +
            fieldBytes(2, label.toByteArray()) +
            fieldBytes(3, "Example".toByteArray()) +
            // Wire enums: algorithm 0 = SHA1, digits 1 = six digits, type 2 = TOTP.
            fieldVarint(4, 0) + fieldVarint(5, 1) + fieldVarint(6, 2)
        val payload = fieldBytes(1, account) +
            fieldVarint(2, 2) + fieldVarint(3, 2) + fieldVarint(4, partIndex.toLong()) + fieldVarint(5, 77)
        return "otpauth-migration://offline?data=" + Base64.getEncoder().encodeToString(payload)
    }

    private fun fieldBytes(field: Int, bytes: ByteArray): ByteArray =
        varint((field * 8 + 2).toLong()) + varint(bytes.size.toLong()) + bytes

    private fun fieldVarint(field: Int, value: Long): ByteArray =
        varint((field * 8).toLong()) + varint(value)

    private fun varint(input: Long): ByteArray {
        var value = input
        val out = ByteArrayOutputStream()
        do {
            val next = (value and 0x7f).toInt()
            value = value ushr 7
            out.write(if (value == 0L) next else next or 0x80)
        } while (value != 0L)
        return out.toByteArray()
    }
}
