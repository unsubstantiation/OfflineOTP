package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.PreferencesExport
import com.fairuniversal.offlineotp.desktop.storage.ExclusiveExportFile
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executor

/** Capture allowlisted settings on the UI thread; write exclusively on a worker. */
class DesktopPreferencesExport(private val state: DesktopVaultState, private val worker: Executor,
    private val dispatch: (Runnable) -> Unit, private val snapshot: () -> PreferencesExport.Export,
    private val changed: () -> Unit, private val write: (File, ByteArray) -> Unit = ExclusiveExportFile::writeNew) {
    enum class Status { WRITING, SAVED, EXISTS, FAILED }
    private class Work(private var data: PreferencesExport.Export?) {
        @Synchronized fun take() = data.also { data = null }
        @Synchronized fun cancel() { data?.zero(); data = null }
    }
    var status: Status? = null
        private set
    var count = 0
        private set
    private var work: Work? = null
    private var closed = false
    fun dismiss() { work?.cancel(); work = null; status = null; count = 0; changed() }
    fun close() { closed = true; dismiss() }
    fun submit(file: File, active: () -> Boolean): Boolean {
        if (closed || state.isLocked || work != null || !active()) return false
        val data = try { snapshot() } catch (_: Exception) { status = Status.FAILED; changed(); return false }
        if (closed || state.isLocked || !active()) { data.zero(); return false }
        val task = Work(data); work = task; count = data.keysWritten; status = Status.WRITING; changed()
        try {
            worker.execute {
                if (state.isLocked) { task.cancel(); return@execute }
                // Once taken, a write may finish even if the UI cancels. No live settings are borrowed.
                val export = task.take() ?: return@execute
                val result = try { write(file, export.bytes); Status.SAVED }
                    catch (_: FileAlreadyExistsException) { Status.EXISTS }
                    catch (_: Exception) { Status.FAILED }
                    finally { export.zero() }
                try { dispatch(Runnable {
                    if (!closed && !state.isLocked && work === task && active()) {
                        work = null; status = result; changed()
                    }
                }) } catch (_: Exception) { task.cancel() }
            }
        } catch (_: Exception) { task.cancel(); work = null; status = Status.FAILED; changed() }
        return true
    }
}
