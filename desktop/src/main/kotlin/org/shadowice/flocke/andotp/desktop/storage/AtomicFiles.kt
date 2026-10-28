package org.shadowice.flocke.andotp.desktop.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Shared crash-safe file replacement for the desktop stores: a unique sibling staging file is
 * force-synced and then promoted with an atomic move (with a non-atomic fallback on file systems
 * that cannot promise one). This is the properties/settings counterpart of [NioAtomicFileStore],
 * which additionally verifies promoted bytes for the larger vault documents.
 */
internal object AtomicFiles {

    @Throws(IOException::class)
    fun writeAtomic(target: File, data: ByteArray) {
        target.parentFile?.mkdirs()
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
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
    }

    private const val STAGING_SUFFIX = ".new-"
}
