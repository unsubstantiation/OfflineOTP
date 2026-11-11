package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.OpenPgpBackupCodec
import com.fairuniversal.offlineotp.Utilities.OpenPgpPort
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.desktop.openpgp.RpgpOpenPgpPort
import com.fairuniversal.offlineotp.desktop.storage.EncryptedBackupFile
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executor

/** UI-thread requests, detached plaintext snapshot, worker encryption/write, guarded UI delivery. */
class DesktopOpenPgpExport(
    private val state: DesktopVaultState,
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val changed: () -> Unit,
    private val port: OpenPgpPort = RpgpOpenPgpPort(),
    private val write: (File, ByteArray) -> Unit = EncryptedBackupFile::writeNew,
    private val snapshot: () -> String = { VaultJson.entriesToString(state.snapshot()) }
) {
    enum class Failure { KEY, EXPORT, EXISTS }
    class Request internal constructor(internal val target: File) {
        var busy = false
            internal set
        var failure: Failure? = null
            internal set
    }
    private class Input(val keyFile: File, val document: String) {
        override fun toString() = "BackupInput(redacted)"
    }
    private class Work(private var input: Input?) {
        private var cancelled = false
        @Synchronized fun take(): Input? = input.also { input = null }
        @Synchronized fun cancel() { cancelled = true; input = null }
        // Cancellation before this point suppresses the write. Once writing starts it may finish.
        @Synchronized fun claimWrite(): Boolean = !cancelled
    }
    var pending: Request? = null
        private set
    var saved = false
        private set
    private var work: Work? = null
    private var closed = false

    fun open(target: File) {
        if (closed || state.isLocked || pending?.busy == true) return
        pending = Request(target)
        saved = false
        changed()
    }
    fun dismiss(request: Request) {
        if (pending !== request) return
        work?.cancel(); work = null; pending = null
        changed()
    }
    fun dismissNotice() { saved = false; changed() }
    fun close() { closed = true; work?.cancel(); work = null; pending = null; saved = false }

    fun submit(request: Request, keyFile: File?): Boolean {
        if (closed || state.isLocked || pending !== request || request.busy) return false
        if (keyFile == null) {
            request.failure = Failure.KEY; changed(); return false
        }
        // Serialization is on the UI thread so the worker never borrows mutable live entries/keys.
        val document = try { snapshot() } catch (_: Exception) {
            request.failure = Failure.EXPORT; changed(); return false
        }
        val task = Work(Input(keyFile, document))
        work = task; request.busy = true; request.failure = null; changed()
        try {
            worker.execute {
                if (state.isLocked) { task.cancel(); return@execute }
                val input = task.take() ?: return@execute
                var encrypted: ByteArray? = null
                val failure = try {
                    val key = input.keyFile.inputStream().use { BoundedInputReader.read(it, BoundedInputReader.MAX_DOCUMENT_BYTES) }
                    encrypted = try { OpenPgpBackupCodec.seal(port, input.document, listOf(key)) } finally { key.fill(0) }
                    if (state.isLocked || !task.claimWrite()) return@execute
                    write(request.target, encrypted)
                    null
                } catch (_: FileAlreadyExistsException) { Failure.EXISTS }
                catch (_: Exception) { Failure.EXPORT }
                finally { encrypted?.fill(0) }
                try { dispatch(Runnable { complete(request, task, failure) }) }
                catch (_: Exception) { task.cancel() }
            }
        } catch (_: Exception) {
            task.cancel()
            complete(request, task, Failure.EXPORT)
        }
        return true
    }

    private fun complete(request: Request, task: Work, failure: Failure?) {
        if (closed || state.isLocked || pending !== request || work !== task) return
        work = null; request.busy = false; request.failure = failure
        if (failure == null) { pending = null; saved = true }
        changed()
    }
}
