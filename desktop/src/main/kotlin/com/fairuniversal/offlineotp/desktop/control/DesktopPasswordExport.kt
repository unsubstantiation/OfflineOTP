package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.desktop.storage.ExclusiveExportFile
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executor

/** UI-thread authority over one detached plan and one explicitly confirmed CSV write. */
class DesktopPasswordExport(private val state: DesktopVaultState, private val worker: Executor,
    private val dispatch: (Runnable) -> Unit, private val changed: () -> Unit,
    private val write: (File, ByteArray) -> Unit = ExclusiveExportFile::writeNew) {
    enum class Status { SELECT, CONFIRM, WRITING, SAVED, EXISTS, FAILED }
    class Request internal constructor(internal val plan: DesktopPasswordExportPlan) {
        val groups get() = plan.groups
        val foreignPasswords get() = plan.foreignPasswords
        val archives get() = plan.archives
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
        val plan = try { DesktopPasswordExportPlan.prepare(state) } catch (_: Exception) { return false }
        if (!active() || closed || state.isLocked || pending != null) { plan.close(); return false }
        pending = Request(plan); changed(); return true
    }
    fun choose(request: Request, index: Int, file: File, active: () -> Boolean): Boolean {
        if (!current(request) || request.status != Status.SELECT || index !in request.groups.indices || !active() || !current(request)) return false
        request.selected = index; request.file = file; request.status = Status.CONFIRM; changed(); return true
    }
    fun dismiss(request: Request) {
        if (pending !== request) return
        work?.cancel(); work = null; request.plan.close(); pending = null; changed()
    }
    fun close() { closed = true; pending?.let(::dismiss) }
    fun confirm(request: Request, active: () -> Boolean): Boolean {
        if (!current(request) || request.status != Status.CONFIRM || !active() || !current(request)) return false
        val file = request.file ?: return false
        val bytes = request.groups[request.selected].takeBytes() ?: return false
        request.plan.close() // Drop unselected secrets as soon as this export is authorized.
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
