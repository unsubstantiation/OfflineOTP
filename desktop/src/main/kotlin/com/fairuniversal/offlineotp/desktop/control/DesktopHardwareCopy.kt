package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.HardwareOathClient
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import java.util.concurrent.Executor

/** Explicit seed-export authority. A worker receives a detached entry, never a live vault entry. */
class DesktopHardwareCopy(private val state: DesktopVaultState, private val worker: Executor,
    private val dispatch: (Runnable) -> Unit, private val changed: () -> Unit,
    private val copy: (CharArray?, Entry, HardwareKeyManager.CancelToken) -> HardwareKeyManager.Result<Unit>) {
    enum class Status { SELECT, CONFIRM, WRITING, COPIED, FAILED, NO_KEY }
    class Request internal constructor(internal val entries: List<Entry>) {
        val labels = entries.map { "${it.getIssuer()} — ${it.getLabel()} (${it.getType()})" }
        var selected = -1
            internal set
        var status = Status.SELECT
            internal set
    }
    private class Work(private var entry: Entry?, private var pin: CharArray?) {
        val token = HardwareKeyManager.CancelToken()
        @Synchronized fun take(): Pair<Entry, CharArray>? {
            val value = entry ?: return null
            val password = pin ?: return null
            entry = null; pin = null
            return value to password
        }
        @Synchronized fun cancel() { token.cancel(); entry?.destroy(); entry = null; pin?.fill(' '); pin = null }
    }
    var pending: Request? = null
        private set
    private var work: Work? = null
    private var closed = false
    private fun current(request: Request) = !closed && !state.isLocked && pending === request
    fun open() {
        if (closed || state.isLocked || pending != null) return
        pending = Request(state.snapshot().filter { HardwareOathClient.canCopy(it) }); changed()
    }
    fun select(request: Request, index: Int, active: () -> Boolean): Boolean {
        if (!current(request) || request.status != Status.SELECT || !active() || !current(request)) return false
        val entry = request.entries.getOrNull(index) ?: return false
        if (state.snapshot().none { it === entry }) return false
        request.selected = index; request.status = Status.CONFIRM; changed(); return true
    }
    fun dismiss(request: Request) {
        if (pending !== request) return
        work?.cancel(); work = null; pending = null; changed()
    }
    fun close() { closed = true; pending?.let(::dismiss) }
    fun submit(request: Request, pin: CharArray, active: () -> Boolean): Boolean {
        if (!current(request) || request.status != Status.CONFIRM || pin.size > InputPolicy.MAX_TEXT_CHARS || !active() || !current(request)) {
            pin.fill(' '); return false
        }
        val source = request.entries.getOrNull(request.selected)
        if (source == null || state.snapshot().none { it === source } || !HardwareOathClient.canCopy(source)) {
            pin.fill(' '); dismiss(request); return false
        }
        val detached = try { Entry(source.toJSON()) } catch (_: Exception) {
            pin.fill(' '); request.status = Status.FAILED; changed(); return false
        }
        if (!active() || !current(request)) { detached.destroy(); pin.fill(' '); return false }
        val task = Work(detached, pin); work = task; request.status = Status.WRITING; changed()
        try {
            worker.execute {
                val input = task.take() ?: return@execute
                val result = try {
                    if (task.token.isCanceled() || state.isLocked) HardwareKeyManager.Result.Canceled
                    else copy(input.second, input.first, task.token)
                } catch (_: Exception) { HardwareKeyManager.Result.Failed }
                finally { input.first.destroy(); input.second.fill(' ') }
                try { dispatch(Runnable {
                    if (current(request) && work === task && active() && current(request)) {
                        work = null
                        request.status = when (result) {
                            is HardwareKeyManager.Result.Ok -> Status.COPIED
                            HardwareKeyManager.Result.NoKey -> Status.NO_KEY
                            else -> Status.FAILED
                        }
                        changed()
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
