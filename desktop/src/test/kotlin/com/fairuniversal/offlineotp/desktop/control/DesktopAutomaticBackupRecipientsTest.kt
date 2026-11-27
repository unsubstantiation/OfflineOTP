package com.fairuniversal.offlineotp.desktop.control

import java.nio.file.Files
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAutomaticBackupRecipientsTest {
    @Test fun addingRecipientPreservesSavedSnapshotsAndReturnsOwnedBuffers() {
        val folder = Files.createTempDirectory("recipients-").toFile()
        try {
            val saved = listOf(byteArrayOf(1, 2))
            val file = File(folder, "new.asc").apply { writeBytes(byteArrayOf(3, 4)) }
            val keys = DesktopAutomaticBackupRecipients.read(saved, listOf(file))
            assertEquals(2, keys.size)
            assertTrue(keys[0].contentEquals(byteArrayOf(1, 2)))
            assertTrue(keys[1].contentEquals(byteArrayOf(3, 4)))
            keys.forEach { it.fill(0) }
            assertTrue(saved[0].contentEquals(byteArrayOf(1, 2)))
            assertTrue(file.readBytes().contentEquals(byteArrayOf(3, 4)))
        } finally { folder.deleteRecursively() }
    }
    @Test fun combinedBoundsDuplicatesAndMissingFilesNeverYieldPartialSelection() {
        val folder = Files.createTempDirectory("recipients-invalid-").toFile()
        try {
            val file = File(folder, "new.asc").apply { writeBytes(byteArrayOf(1)) }
            val saved = listOf(byteArrayOf(1))
            for ((previous, files) in listOf(saved to listOf(file), listOf(ByteArray(32768)) to listOf(file),
                saved to listOf(File(folder, "missing")), emptyList<ByteArray>() to emptyList<File>())) {
                var rejected = false
                try { DesktopAutomaticBackupRecipients.read(previous, files) } catch (_: Exception) { rejected = true }
                assertTrue(rejected)
            }
            assertTrue(saved.single().contentEquals(byteArrayOf(1)))
        } finally { folder.deleteRecursively() }
    }
}
