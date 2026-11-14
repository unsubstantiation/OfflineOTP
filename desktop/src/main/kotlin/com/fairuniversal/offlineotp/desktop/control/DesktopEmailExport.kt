package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.desktop.storage.ExclusiveExportFile
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executor

/** Plaintext export requires a current, identity-bound confirmation. UI-thread owner. */
class DesktopEmailExport(private val state: DesktopVaultState, private val worker: Executor,
    private val dispatch: (Runnable) -> Unit, private val changed: () -> Unit,
    private val write: (File, ByteArray) -> Unit = ExclusiveExportFile::writeNew) {
    enum class Status { CONFIRM, WRITING, SAVED, EXISTS, FAILED }
    class Request internal constructor(internal val file: File) {
        var status = Status.CONFIRM
            internal set
        var count = 0
            internal set
    }
    private class Work(private var bytes: ByteArray?) {
        @Synchronized fun take(): ByteArray? = bytes.also { bytes = null }
        @Synchronized fun cancel() { bytes?.fill(0); bytes = null }
    }
    var pending: Request? = null
        private set
    private var work: Work? = null
    private var closed = false
    fun open(file: File) {
        if (closed || state.isLocked || pending != null) return
        pending = Request(file); changed()
    }
    fun dismiss(request: Request) {
        if (pending !== request) return
        work?.cancel(); work = null; pending = null; changed()
    }
    fun close() { closed = true; pending?.let(::dismiss) }
    private fun current(request: Request) = !closed && !state.isLocked && pending === request

    fun confirm(request: Request, active: () -> Boolean): Boolean {
        if (!current(request) || request.status != Status.CONFIRM || !active()) return false
        // Never borrow live entries on a worker. Snapshot only after explicit plaintext consent.
        val entries = state.snapshot().filter { it.isEmail() }
        val bytes = try { EmailAccounts.exportBackup(entries) } catch (_: Exception) {
            request.status = Status.FAILED; changed(); return false
        }
        if (!current(request) || !active()) { bytes.fill(0); return false }
        val task = Work(bytes); work = task
        request.count = entries.size; request.status = Status.WRITING; changed()
        try {
            worker.execute {
                if (state.isLocked) { task.cancel(); return@execute }
                val data = task.take() ?: return@execute
                // Cancellation cannot interrupt a write already authorized and started.
                val result = try { write(request.file, data); Status.SAVED }
                    catch (_: FileAlreadyExistsException) { Status.EXISTS }
                    catch (_: Exception) { Status.FAILED }
                    finally { data.fill(0) }
                try { dispatch(Runnable {
                    if (current(request) && work === task && active()) {
                        work = null; request.status = result; changed()
                    }
                }) } catch (_: Exception) { task.cancel() }
            }
        } catch (_: Exception) {
            task.cancel(); work = null; request.status = Status.FAILED; changed()
        }
        return true
    }
}
