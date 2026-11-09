package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.PasswordImport
import com.fairuniversal.offlineotp.Utilities.BrowserPasswordCsv
import java.io.File
import java.util.concurrent.Executor

/** Detached worker decoding; explicit UI-thread confirmation transfers ownership to EntryEditor. */
class DesktopPasswordImport(
    private val state: DesktopVaultState,
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val changed: () -> Unit,
    /** Must consume all imported entries on both success and failure. */
    private val install: (List<Entry>) -> Int?,
    private val decode: (File, PasswordImport.Mode) -> ArrayList<Entry> = ::readPasswords
) {
    class Request internal constructor(internal val source: File) {
        var busy = false
            internal set
        var failed = false
            internal set
        internal var entries: ArrayList<Entry>? = null
        val count: Int? get() = entries?.size
    }
    private class Work {
        private var cancelled = false
        private var result: ArrayList<Entry>? = null
        @Synchronized fun active(): Boolean = !cancelled
        @Synchronized fun offer(entries: ArrayList<Entry>): Boolean {
            if (cancelled) { destroy(entries); return false }
            result = entries; return true
        }
        @Synchronized fun take(): ArrayList<Entry>? = result.also { result = null }
        @Synchronized fun cancel() { cancelled = true; result?.let(::destroy); result = null }
    }
    var pending: Request? = null
        private set
    var importedCount: Int? = null
        private set
    private var closed = false
    private var work: Work? = null

    fun open(source: File) {
        if (closed || state.isLocked || pending != null) return
        importedCount = null; pending = Request(source); changed()
    }
    fun dismiss(request: Request) {
        if (pending !== request) return
        work?.cancel(); work = null
        request.entries?.let(::destroy); request.entries = null
        pending = null; changed()
    }
    fun dismissNotice() { importedCount = null; changed() }
    fun close() {
        closed = true; work?.cancel(); work = null
        pending?.entries?.let(::destroy); pending?.entries = null
        pending = null; importedCount = null
    }
    fun submit(request: Request, mode: PasswordImport.Mode, active: () -> Boolean): Boolean {
        if (!valid(request) || request.busy || request.count != null || !active()) return false
        val task = Work()
        work = task; request.busy = true; request.failed = false; changed()
        try {
            worker.execute {
                if (state.isLocked) { task.cancel(); return@execute }
                if (!task.active()) return@execute
                val entries = try { decode(request.source, mode) } catch (_: Exception) { null }
                if (entries != null && !task.offer(entries)) return@execute
                if (state.isLocked) { task.cancel(); return@execute }
                try { dispatch(Runnable {
                    if (!valid(request) || work !== task || !active()) { task.cancel(); return@Runnable }
                    work = null; request.busy = false
                    request.entries = task.take()
                    request.failed = request.entries == null
                    changed()
                }) } catch (_: Exception) { task.cancel() }
            }
        } catch (_: Exception) {
            task.cancel(); work = null; request.busy = false; request.failed = true; changed()
        }
        return true
    }
    /** Confirmation merges into the current vault, deduplicating through EntryEditor. */
    fun confirm(request: Request, active: () -> Boolean): Boolean {
        if (!valid(request) || request.busy || !active()) return false
        val entries = request.entries ?: return false
        request.entries = null
        try {
            importedCount = install(entries) ?: throw IllegalStateException("Import failed")
            pending = null
        } catch (_: Exception) { request.failed = true }
        changed()
        return pending == null
    }
    private fun valid(request: Request) = !closed && !state.isLocked && pending === request

    companion object {
        private fun destroy(entries: ArrayList<Entry>) { entries.forEach { it.destroy() }; entries.clear() }
        private fun readPasswords(source: File, mode: PasswordImport.Mode): ArrayList<Entry> {
            val bytes = source.inputStream().use { BoundedInputReader.read(it, BrowserPasswordCsv.MAX_FILE_BYTES) }
            return try { PasswordImport.prepare(bytes, source.name, mode) }
                finally { bytes.fill(0) }
        }
    }
}
