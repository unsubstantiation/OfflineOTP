package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import java.io.File
import java.util.concurrent.Executor

/** FairEmail plaintext JSON, gzip agent backup or encrypted export; merge only. */
class DesktopEmailImport(
    state: DesktopVaultState, worker: Executor, dispatch: (Runnable) -> Unit, changed: () -> Unit,
    install: (List<Entry>) -> Int?,
    decode: (File, String) -> ArrayList<Entry> = ::readEmail
) : DesktopFileImport<String>(state, worker, dispatch, changed, install, decode) {
    companion object {
        private fun readEmail(source: File, password: String): ArrayList<Entry> {
            require(password.length <= InputPolicy.MAX_TEXT_CHARS)
            val bytes = source.inputStream().use { BoundedInputReader.read(it, EmailAccounts.MAX_IMPORT_BYTES) }
            return try { ArrayList(EmailAccounts.importEntries(bytes, password.ifEmpty { null })) }
                finally { bytes.fill(0) }
        }
    }
}
