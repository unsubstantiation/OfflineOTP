package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import java.io.File
import java.util.concurrent.Executor

/** Detached worker decoding; explicit UI-thread confirmation transfers ownership to EntryEditor. */
open class DesktopFileImport<Input : Any>(
    private val state: DesktopVaultState,
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val changed: () -> Unit,
    /** Must consume all imported entries on both success and failure. */
    private val install: (List<Entry>) -> Int?,
    private val decode: (File, Input) -> ArrayList<Entry>
) {
    class Request internal constructor(internal val source: File) {
        var busy = false
            internal set
        var failed = false
            internal set
        internal var entries: ArrayList<Entry>? = null
        val count: Int? get() = entries?.size
    }
    private class Work<Input : Any>(private var input: Input?) {
        private var cancelled = false
        private var result: ArrayList<Entry>? = null
        @Synchronized fun takeInput(): Input? = input.also { input = null }
        @Synchronized fun offer(entries: ArrayList<Entry>): Boolean {
            if (cancelled) { destroy(entries); return false }
            result = entries; return true
        }
        @Synchronized fun take(): ArrayList<Entry>? = result.also { result = null }
        @Synchronized fun cancel() { cancelled = true; input = null; result?.let(::destroy); result = null }
    }
    var pending: Request? = null
        private set
    var importedCount: Int? = null
        private set
    private var closed = false
    private var work: Work<Input>? = null

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
    fun submit(request: Request, input: Input, active: () -> Boolean): Boolean {
        if (!valid(request) || request.busy || request.count != null || !active()) return false
        val task = Work(input)
        work = task; request.busy = true; request.failed = false; changed()
        try {
            worker.execute {
                if (state.isLocked) { task.cancel(); return@execute }
                val value = task.takeInput() ?: return@execute
                val entries = try { decode(request.source, value) } catch (_: Exception) { null }
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
    fun confirm(request: Request, active: () -> Boolean): Boolean = confirmUsing(request, active, install)

    /** Specialized backup hosts may supply a replace transaction; it must consume entries on every outcome. */
    protected fun confirmUsing(request: Request, active: () -> Boolean, commit: (List<Entry>) -> Int?): Boolean {
        if (!valid(request) || request.busy || !active()) return false
        val entries = request.entries ?: return false
        request.entries = null
        try {
            importedCount = commit(entries) ?: throw IllegalStateException("Import failed")
            pending = null
        } catch (_: Exception) { request.failed = true }
        changed()
        return pending == null
    }
    private fun valid(request: Request) = !closed && !state.isLocked && pending === request

    companion object {
        private fun destroy(entries: ArrayList<Entry>) { entries.forEach { it.destroy() }; entries.clear() }
    }
}
