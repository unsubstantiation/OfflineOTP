package com.fairuniversal.offlineotp.desktop.storage

import java.io.File

/** Ciphertext-only facade over the exclusive export writer. */
object EncryptedBackupFile {
    fun writeNew(target: File, ciphertext: ByteArray) = ExclusiveExportFile.writeNew(target, ciphertext)
}
