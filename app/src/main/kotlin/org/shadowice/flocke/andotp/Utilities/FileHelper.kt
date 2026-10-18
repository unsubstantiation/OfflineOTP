package org.shadowice.flocke.andotp.Utilities

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Atomic private-file replacement; callers must hold [DatabaseHelper.DatabaseFileLock]. */
internal object FileHelper {
    @JvmStatic
    fun exists(file: File): Boolean = file.exists() || File(file.path + ".bak").exists()

    @JvmStatic
    @Throws(IOException::class)
    fun readFileToBytes(file: File): ByteArray {
        return AtomicFile(file).openRead().use { input ->
            BoundedInputReader.read(input, BoundedInputReader.MAX_DOCUMENT_BYTES + 1024)
        }
    }

    /** Writes [data] through a synced [AtomicFile], promotes it, and verifies the promoted bytes match. */
    @JvmStatic
    @Throws(IOException::class)
    fun writeBytesToFile(file: File, data: ByteArray) {
        val atomic = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            val stream = atomic.startWrite()
            output = stream
            stream.write(data)
            stream.fd.sync()
            atomic.finishWrite(stream)
            output = null
            if (!readFileToBytes(file).contentEquals(data)) {
                throw IOException("Atomic write verification failed")
            }
        } catch (error: Exception) {
            // Only IOException and RuntimeException can leave this block; Errors pass through untouched.
            output?.let { atomic.failWrite(it) }
            throw error
        }
    }

    @JvmStatic
    fun delete(file: File) {
        AtomicFile(file).delete()
        // Cover both legacy and current platform AtomicFile staging names.
        File(file.path + ".new").delete()
    }
}
