package org.shadowice.flocke.andotp.desktop.storage

import org.shadowice.flocke.andotp.Utilities.AtomicByteStore
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Desktop [AtomicByteStore]: the `java.nio` equivalent of Android's `android.util.AtomicFile`
 * adapter. Writes go to a unique sibling staging file, are force-synced, then promoted with an
 * atomic move (with a non-atomic fallback on file systems that reject it) and re-read for
 * verification, so a crash or failed promotion never leaves a half-written vault.
 */
class NioAtomicFileStore(private val target: File) : AtomicByteStore {

    override fun exists(): Boolean = target.exists()

    @Throws(IOException::class)
    override fun read(): ByteArray {
        // Same bound as the Android FileHelper reader: the document limit plus a small slack.
        FileInputStream(target).use { input ->
            return BoundedInputReader.read(input, BoundedInputReader.MAX_DOCUMENT_BYTES + 1024)
        }
    }

    @Throws(IOException::class)
    override fun writeAtomic(data: ByteArray) {
        val staging = File(target.parentFile, target.name + STAGING_SUFFIX + UUID.randomUUID())
        try {
            FileOutputStream(staging).use { output ->
                output.write(data)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(staging.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                // Some file systems (e.g. certain network mounts) cannot promise atomicity.
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (!read().contentEquals(data)) {
                throw IOException("Atomic write verification failed for ${target.name}")
            }
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
    }

    override fun delete() {
        target.delete()
        target.parentFile?.listFiles { file -> file.name.startsWith(target.name + STAGING_SUFFIX) }
            ?.forEach { it.delete() }
    }

    private companion object {
        const val STAGING_SUFFIX = ".new-"
    }
}
