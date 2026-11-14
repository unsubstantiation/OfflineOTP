package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.PasswordImport
import com.fairuniversal.offlineotp.Utilities.BrowserPasswordCsv
import java.io.File
import java.util.concurrent.Executor

class DesktopPasswordImport(
    state: DesktopVaultState, worker: Executor, dispatch: (Runnable) -> Unit, changed: () -> Unit,
    install: (List<Entry>) -> Int?,
    decode: (File, PasswordImport.Mode) -> ArrayList<Entry> = ::readPasswords
) : DesktopFileImport<PasswordImport.Mode>(state, worker, dispatch, changed, install, decode) {
    companion object {
        private fun readPasswords(source: File, mode: PasswordImport.Mode): ArrayList<Entry> {
            val bytes = source.inputStream().use { BoundedInputReader.read(it, BrowserPasswordCsv.MAX_FILE_BYTES) }
            return try { PasswordImport.prepare(bytes, source.name, mode) }
                finally { bytes.fill(0) }
        }
    }
}
