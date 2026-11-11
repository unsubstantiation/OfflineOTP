package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.Utilities.OpenPgpBackupCodec
import com.fairuniversal.offlineotp.Utilities.OpenPgpPort
import com.fairuniversal.offlineotp.desktop.openpgp.RpgpOpenPgpPort
import java.io.File
import java.util.concurrent.Executor

/** File-key OpenPGP import. No provider UI or hardware key access is implied. */
class DesktopOpenPgpImport(
    state: DesktopVaultState, worker: Executor, dispatch: (Runnable) -> Unit, changed: () -> Unit,
    install: (List<Entry>) -> Int?, port: OpenPgpPort = RpgpOpenPgpPort()
) : DesktopFileImport<DesktopOpenPgpImport.Input>(state, worker, dispatch, changed, install,
    { source, input -> readBackup(source, input, port) }) {
    class Input(val passphrase: String, val keyFile: File?) {
        override fun toString() = "OpenPgpImportInput(redacted)"
    }
    companion object {
        private fun readBackup(source: File, input: Input, port: OpenPgpPort): ArrayList<Entry> {
            require(input.passphrase.length <= InputPolicy.MAX_TEXT_CHARS)
            val keyFile = requireNotNull(input.keyFile)
            val message = source.inputStream().use { BoundedInputReader.read(it, BoundedInputReader.MAX_DOCUMENT_BYTES) }
            var key: ByteArray? = null
            val passphrase = input.passphrase.takeIf { it.isNotEmpty() }?.toCharArray()
            try {
                val material = keyFile.inputStream().use { BoundedInputReader.read(it, BoundedInputReader.MAX_DOCUMENT_BYTES) }
                key = material
                val entries = VaultJson.stringToEntries(OpenPgpBackupCodec.open(port, message, material, passphrase))
                try {
                    require(entries.isNotEmpty() && entries.toHashSet().size == entries.size)
                    return entries
                } catch (error: Exception) { entries.forEach { it.destroy() }; throw error }
            } finally { message.fill(0); key?.fill(0); passphrase?.fill('\u0000') }
        }
    }
}
