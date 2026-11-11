package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.Utilities.LinScpCredentials
import java.io.File
import java.util.concurrent.Executor

class DesktopLinScpImport(
    state: DesktopVaultState, worker: Executor, dispatch: (Runnable) -> Unit, changed: () -> Unit,
    install: (List<Entry>) -> Int?,
    decode: (File, Input) -> ArrayList<Entry> = ::readCredentials
) : DesktopFileImport<DesktopLinScpImport.Input>(state, worker, dispatch, changed, install, decode) {
    class Input(val password: String, val masterKeyFile: File? = null) {
        override fun toString() = "LinScpImportInput(redacted)"
    }
    companion object {
        private fun readCredentials(source: File, input: Input): ArrayList<Entry> {
            require(input.password.length <= InputPolicy.MAX_TEXT_CHARS)
            val bytes = source.inputStream().use { BoundedInputReader.read(it, LinScpCredentials.MAX_FILE_BYTES) }
            var key: ByteArray? = null
            try {
                // Encrypted vaults carry their own KDF; never read an unrelated selected session key.
                if (!LinScpCredentials.isEncryptedVault(bytes)) {
                    key = input.masterKeyFile?.inputStream()?.use { BoundedInputReader.read(it, 4096) }
                }
                return LinScpCredentials.importBytes(bytes, input.password.ifEmpty { null }, key)
            } finally { bytes.fill(0); key?.fill(0) }
        }
    }
}
