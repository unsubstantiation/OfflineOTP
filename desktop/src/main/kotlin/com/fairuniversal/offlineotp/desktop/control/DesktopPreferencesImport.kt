package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.PreferencesExport
import com.fairuniversal.offlineotp.desktop.storage.DesktopPreferencesImportPlan
import java.io.File
import java.util.concurrent.Executor

/** Worker preparation followed by an identity/lifetime-checked UI-thread settings transaction. */
class DesktopPreferencesImport(private val state: DesktopVaultState, private val worker: Executor,
    private val dispatch: (Runnable) -> Unit, private val changed: () -> Unit,
    private val apply: (DesktopPreferencesImportPlan) -> Boolean,
    private val read: (File) -> DesktopPreferencesImportPlan = ::readPlan) {
    class Request internal constructor() {
        var busy = true
            internal set
        var failed = false
            internal set
        var plan: DesktopPreferencesImportPlan? = null
            internal set
    }
    var pending: Request? = null
        private set
    var appliedCount: Int? = null
        private set
    private var closed = false
    private class Work { @Volatile var cancelled = false }
    private var work: Work? = null
    fun dismiss(request: Request) {
        if (pending !== request) return
        work?.cancelled = true; work = null; request.plan = null; pending = null; changed()
    }
    fun dismissNotice() { appliedCount = null; changed() }
    fun close() { closed = true; pending?.let(::dismiss); appliedCount = null }
    fun open(source: File, active: () -> Boolean): Boolean {
        if (closed || state.isLocked || pending != null || !active()) return false
        val request = Request(); val task = Work()
        pending = request; work = task; appliedCount = null; changed()
        try {
            worker.execute {
                if (task.cancelled || state.isLocked) return@execute
                val plan = try { read(source) } catch (_: Exception) { null }
                if (task.cancelled || state.isLocked) return@execute
                try { dispatch(Runnable {
                    if (valid(request) && work === task && active()) {
                        work = null; request.busy = false; request.plan = plan; request.failed = plan == null; changed()
                    }
                }) } catch (_: Exception) { task.cancelled = true }
            }
        } catch (_: Exception) { work = null; request.busy = false; request.failed = true; changed() }
        return true
    }
    fun confirm(request: Request, active: () -> Boolean, refresh: (DesktopPreferencesImportPlan) -> Unit): Boolean {
        if (!valid(request) || request.busy || !active()) return false
        val plan = request.plan ?: return false
        val saved = try { apply(plan) } catch (_: Exception) { false }
        if (!saved) { request.failed = true; changed(); return false }
        // The old deadline must still hold before any imported timeout can replace it.
        if (!valid(request) || !active()) return false
        pending = null; request.plan = null; appliedCount = plan.applied
        refresh(plan); changed(); return true
    }
    private fun valid(request: Request) = !closed && !state.isLocked && pending === request
    companion object {
        private fun readPlan(file: File): DesktopPreferencesImportPlan {
            val bytes = file.inputStream().use { BoundedInputReader.read(it, PreferencesExport.MAX_BYTES) }
            return try { DesktopPreferencesImportPlan.prepare(bytes) } finally { bytes.fill(0) }
        }
    }
}
