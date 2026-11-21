package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.desktop.storage.ExclusiveExportFile
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executor

/** Exact-entry archive selection; copy stored bytes only after explicit plaintext confirmation. */
class DesktopArchiveExport(private val state: DesktopVaultState, private val worker: Executor,
    private val dispatch: (Runnable) -> Unit, private val changed: () -> Unit,
    private val write: (File, ByteArray) -> Unit = ExclusiveExportFile::writeNew) {
    enum class Status { SELECT, CONFIRM, WRITING, SAVED, EXISTS, FAILED }
    class Request internal constructor(internal val entries: List<Entry>) {
        val labels: List<String> = entries.map { it.getLabel() }
        var status = Status.SELECT
            internal set
        var selected = -1
            internal set
        internal var file: File? = null
    }
    private class Work(private var bytes: ByteArray?) {
        @Synchronized fun take() = bytes.also { bytes = null }
        @Synchronized fun cancel() { bytes?.fill(0); bytes = null }
    }
    var pending: Request? = null
        private set
    private var work: Work? = null
    private var closed = false
    private fun current(request: Request) = !closed && !state.isLocked && pending === request
    fun open(active: () -> Boolean): Boolean {
        if (closed || state.isLocked || pending != null || !active()) return false
        val entries = state.snapshot().filter { it.isPasswordArchive() }
        if (!active() || closed || state.isLocked || pending != null) return false
        pending = Request(entries); changed(); return true
    }
    fun choose(request: Request, index: Int, file: File, active: () -> Boolean): Boolean {
        if (!current(request) || request.status != Status.SELECT || index !in request.entries.indices || !active() || !current(request)) return false
        request.selected = index; request.file = file; request.status = Status.CONFIRM; changed(); return true
    }
    fun dismiss(request: Request) {
        if (pending !== request) return
        work?.cancel(); work = null; pending = null; changed()
    }
    fun close() { closed = true; pending?.let(::dismiss) }
    fun confirm(request: Request, active: () -> Boolean): Boolean {
        if (!current(request) || request.status != Status.CONFIRM || !active() || !current(request)) return false
        val file = request.file ?: return false
        val entry = request.entries.getOrNull(request.selected) ?: return false
        if (state.snapshot().none { it === entry }) { dismiss(request); return false }
        val bytes = try { entry.exportPasswordArchive() } catch (_: Exception) {
            request.status = Status.FAILED; changed(); return false
        }
        if (!active() || !current(request)) { bytes.fill(0); return false }
        val task = Work(bytes); work = task; request.status = Status.WRITING; changed()
        try {
            worker.execute {
                if (state.isLocked) { task.cancel(); return@execute }
                val data = task.take() ?: return@execute
                val result = try { write(file, data); Status.SAVED }
                    catch (_: FileAlreadyExistsException) { Status.EXISTS }
                    catch (_: Exception) { Status.FAILED }
                    finally { data.fill(0) }
                try { dispatch(Runnable {
                    if (current(request) && work === task && active() && current(request)) {
                        work = null; request.status = result; changed()
                    }
                }) } catch (_: Exception) { task.cancel() }
            }
        } catch (_: Exception) {
            task.cancel()
            if (current(request) && work === task) { work = null; request.status = Status.FAILED; changed() }
        }
        return true
    }
}
