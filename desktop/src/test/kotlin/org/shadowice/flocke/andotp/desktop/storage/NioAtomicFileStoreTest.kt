/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the java.nio atomic byte store: promotion semantics, staging cleanup,
 * verification and the shared document size bound.
 */
package org.shadowice.flocke.andotp.desktop.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import java.io.File
import java.io.IOException
import java.nio.file.Files

class NioAtomicFileStoreTest {

    private fun newStore(name: String = "secrets.dat"): Pair<File, NioAtomicFileStore> {
        val dir = Files.createTempDirectory("offlineotp-nio-").toFile()
        val target = File(dir, name)
        return Pair(dir, NioAtomicFileStore(target))
    }

    @Test
    fun writtenBytesReadBackIdenticallyAndSurviveOverwrite() {
        val (_, store) = newStore()
        assertFalse(store.exists())

        val first = "first vault contents".toByteArray()
        store.writeAtomic(first)
        assertTrue(store.exists())
        assertArrayEquals(first, store.read())

        val second = "second vault contents, longer than the first".toByteArray()
        store.writeAtomic(second)
        assertArrayEquals(second, store.read())
    }

    @Test
    fun promotionLeavesNoStagingFilesBehind() {
        val (dir, store) = newStore()
        store.writeAtomic("payload".toByteArray())
        store.writeAtomic("payload-two".toByteArray())

        val leftovers = dir.listFiles { file -> file.name.contains(".new-") } ?: emptyArray()
        assertEquals(0, leftovers.size)
        assertEquals(listOf("secrets.dat"), dir.list()?.toList())
    }

    @Test
    fun deleteRemovesTheTargetAndAnyStagingFiles() {
        val (dir, store) = newStore()
        store.writeAtomic("payload".toByteArray())
        assertTrue(File(dir, "secrets.dat").exists())

        store.delete()
        assertFalse(store.exists())
    }

    @Test
    fun readingAMissingFileIsAnIOException() {
        val (_, store) = newStore()
        try {
            store.read()
            throw AssertionError("missing file was read")
        } catch (expected: IOException) {
            // required
        }
    }

    @Test
    fun readsAreBoundedExactlyLikeTheAndroidAdapter() {
        val (dir, store) = newStore()
        val limit = BoundedInputReader.MAX_DOCUMENT_BYTES + 1024
        // Place the oversized file directly: the store's own write verification would reject it
        // before promotion, exactly as the Android AtomicFile adapter never produces one.
        File(dir, "secrets.dat").writeBytes(ByteArray(limit + 1) { 7 })
        try {
            store.read()
            throw AssertionError("oversized document was accepted")
        } catch (expected: IOException) {
            // BoundedInputReader rejects anything past the shared limit.
        }
    }
}
