package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.AutomaticBackupCredentialCodec
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import java.io.File

/** Adds selected files to saved public-certificate snapshots, never silently replacing them. */
object DesktopAutomaticBackupRecipients {
    /** Returned buffers belong to the caller. On failure all intermediate copies are wiped. */
    fun read(saved: List<ByteArray>, files: List<File>): List<ByteArray> {
        val owned = arrayListOf<ByteArray>()
        try {
            require(saved.size + files.size in 1..64)
            var remaining = AutomaticBackupCredentialCodec.MAX_RECIPIENT_BYTES
            for (key in saved) {
                require(key.isNotEmpty() && key.size <= remaining)
                owned.add(key.copyOf()); remaining -= key.size
            }
            for (file in files) {
                require(remaining > 0)
                val key = file.inputStream().use { BoundedInputReader.read(it, remaining) }
                owned.add(key); remaining -= key.size
            }
            require(AutomaticBackupCredentialCodec.validRecipients(owned))
            return owned
        } catch (failure: Exception) {
            owned.forEach { it.fill(0) }
            throw failure
        }
    }
}
