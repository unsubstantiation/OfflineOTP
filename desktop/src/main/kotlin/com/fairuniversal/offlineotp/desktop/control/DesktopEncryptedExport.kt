package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupCodec
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.desktop.storage.EncryptedBackupFile
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executor

/** UI-thread requests, detached plaintext snapshot, worker encryption/write, guarded UI delivery. */
class DesktopEncryptedExport(
    private val state: DesktopVaultState,
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val changed: () -> Unit,
    private val seal: (String, String) -> ByteArray = EncryptedBackupCodec::seal,
    private val write: (File, ByteArray) -> Unit = EncryptedBackupFile::writeNew,
    private val snapshot: () -> String = { VaultJson.entriesToString(state.snapshot()) }
) {
    enum class Failure { PASSWORD, EXPORT, EXISTS }
    class Request internal constructor(internal val target: File) {
        var busy = false
            internal set
        var failure: Failure? = null
            internal set
    }
    private class Input(val password: String, val document: String) {
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

    fun submit(request: Request, password: String, confirmation: String): Boolean {
        if (closed || state.isLocked || pending !== request || request.busy) return false
        if (password.isEmpty() || password.length > InputPolicy.MAX_TEXT_CHARS || password != confirmation) {
            request.failure = Failure.PASSWORD; changed(); return false
        }
        // Serialization is on the UI thread so the worker never borrows mutable live entries/keys.
        val document = try { snapshot() } catch (_: Exception) {
            request.failure = Failure.EXPORT; changed(); return false
        }
        val task = Work(Input(password, document))
        work = task; request.busy = true; request.failure = null; changed()
        try {
            worker.execute {
                if (state.isLocked) { task.cancel(); return@execute }
                val input = task.take() ?: return@execute
                var encrypted: ByteArray? = null
                val failure = try {
                    encrypted = seal(input.password, input.document)
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
