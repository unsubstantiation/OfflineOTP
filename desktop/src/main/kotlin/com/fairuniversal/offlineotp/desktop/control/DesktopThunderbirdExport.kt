package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.ThunderbirdImport
import com.fairuniversal.offlineotp.desktop.storage.ExclusiveExportFile
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executor

/**
 * Selective Thunderbird CSV exporter for the desktop client, mirroring Android and iOS.
 *
 * First presents all exportable `EMAIL` and `PASSWORD` entries so the user chooses which accounts
 * to include, then requires explicit plaintext confirmation bound to the chosen destination file,
 * writes with `CREATE_NEW`, and scrubs the serialized CSV bytes in `finally`.
 */
class DesktopThunderbirdExport(
    private val state: DesktopVaultState,
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val changed: () -> Unit,
    private val write: (File, ByteArray) -> Unit = ExclusiveExportFile::writeNew
) {
    enum class Status { SELECT, CONFIRM, WRITING, SAVED, EXISTS, FAILED }

    data class Candidate(val entryId: Long, val label: String)

    class Request internal constructor(val candidates: List<Candidate>) {
        var status: Status = Status.SELECT
            internal set
        var selectedIds: Set<Long> = emptySet()
            internal set
        internal var file: File? = null
        var count: Int = 0
            internal set
    }

    private class Work(private var bytes: ByteArray?, generation: Long) {
        private val delivery = com.fairuniversal.offlineotp.domain.InterchangeWorkflow(generation).also { check(it.preview(0)) }
        @Synchronized fun take(generation: Long, ready: Boolean): ByteArray? {
            if (!delivery.consume(generation, ready)) { cancel(); return null }
            return bytes.also { bytes = null }
        }
        @Synchronized fun cancel() { delivery.cancel(); bytes?.fill(0); bytes = null }
    }

    var pending: Request? = null
        private set
    private var work: Work? = null
    private var closed = false

    /** Opens selection over exportable live `EMAIL` and `PASSWORD` entries; returns false when none exist. */
    fun open(active: () -> Boolean): Boolean {
        if (closed || state.isLocked || pending != null || !active()) return false
        val candidates = state.snapshot()
            .filter { ThunderbirdImport.isExportable(it) }
            .map { entry ->
                val kindTag = if (entry.isEmail()) "Email" else "Password"
                val name = listOf(entry.getIssuer().trim(), entry.getLabel().trim())
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString(" — ")
                    .ifEmpty { kindTag }
                Candidate(entry.getListId(), "[$kindTag] $name")
            }
        if (candidates.isEmpty()) return false
        pending = Request(candidates)
        changed()
        return true
    }

    fun choose(request: Request, selectedIndices: Set<Int>, target: File, active: () -> Boolean): Boolean {
        if (!current(request) || request.status != Status.SELECT || !active()) return false
        val chosenIds = selectedIndices
            .filter { it in request.candidates.indices }
            .map { request.candidates[it].entryId }
            .toSet()
        if (chosenIds.isEmpty()) return false
        request.selectedIds = chosenIds
        request.file = target
        request.count = chosenIds.size
        request.status = Status.CONFIRM
        changed()
        return true
    }

    fun confirm(request: Request, active: () -> Boolean): Boolean {
        if (!current(request) || request.status != Status.CONFIRM || !active()) return false
        val target = request.file ?: return false
        val selected = state.snapshot().filter {
            ThunderbirdImport.isExportable(it) && it.getListId() in request.selectedIds
        }
        if (selected.isEmpty()) {
            request.status = Status.FAILED
            changed()
            return false
        }
        val bytes = try {
            ThunderbirdImport.exportCsv(selected)
        } catch (_: Exception) {
            request.status = Status.FAILED
            changed()
            return false
        }
        if (!current(request) || !active()) {
            bytes.fill(0)
            return false
        }
        val task = Work(bytes, state.generation)
        work = task
        request.count = selected.size
        request.status = Status.WRITING
        changed()
        try {
            worker.execute {
                if (state.isLocked) { task.cancel(); return@execute }
                val data = task.take(state.generation, !state.isLocked) ?: return@execute
                val result = try {
                    write(target, data)
                    Status.SAVED
                } catch (_: FileAlreadyExistsException) {
                    Status.EXISTS
                } catch (_: Exception) {
                    Status.FAILED
                } finally {
                    data.fill(0)
                }
                try {
                    dispatch(Runnable {
                        if (current(request) && work === task && active()) {
                            work = null
                            request.status = result
                            changed()
                        }
                    })
                } catch (_: Exception) {
                    task.cancel()
                }
            }
        } catch (_: Exception) {
            task.cancel()
            work = null
            request.status = Status.FAILED
            changed()
        }
        return true
    }

    fun dismiss(request: Request) {
        if (pending !== request) return
        work?.cancel()
        work = null
        pending = null
        changed()
    }

    fun close() {
        closed = true
        pending?.let(::dismiss)
    }

    private fun current(request: Request): Boolean =
        !closed && !state.isLocked && pending === request
}
