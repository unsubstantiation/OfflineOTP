package com.fairuniversal.offlineotp.desktop.storage

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Write ciphertext only, exclusively creating a new destination; never truncate an existing file. */
object EncryptedBackupFile {
    fun writeNew(target: File, ciphertext: ByteArray) {
        val path = target.toPath().toAbsolutePath()
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val attributes = if (Files.getFileStore(path.parent).supportsFileAttributeView("posix"))
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        else emptyArray()
        FileChannel.open(path, options, *attributes).use { channel ->
            // On non-POSIX systems inherited directory ACLs remain the effective boundary.
            PrivateFiles.restrict(path.toFile())
            val bytes = ByteBuffer.wrap(ciphertext)
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        // A failed/interrupted write can leave a partial encrypted file; do not delete a path
        // that another process could have replaced. The caller reports failure, never success.
    }
}
