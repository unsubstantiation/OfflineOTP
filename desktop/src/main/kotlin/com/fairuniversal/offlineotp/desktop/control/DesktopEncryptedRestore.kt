package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupCodec
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.domain.RestoreWorkflow
import java.io.File
import java.util.concurrent.Executor

/** Detached worker decoding; explicit UI-thread confirmation transfers ownership to the coordinator. */
class DesktopEncryptedRestore(
    private val state: DesktopVaultState,
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val changed: () -> Unit,
    /** Must consume all imported entries on both success and failure. */
    private val install: (ArrayList<Entry>, Boolean) -> Int,
    private val decode: (File, String) -> ArrayList<Entry> = ::readBackup,
    private val decodeSelected: (File, String, String, String) -> ArrayList<Entry> = ::readSelectedBackup
) {
    class Request internal constructor(internal val source: File, internal val authorityKey: javax.crypto.SecretKey) {
        var busy = false
            internal set
        var failed = false
            internal set
        internal var entries: ArrayList<Entry>? = null
        internal val workflow = RestoreWorkflow()
        internal var ticket = 0L
        val count: Int? get() = entries?.size
    }
    private class Work(private var password: String?) {
        private var cancelled = false
        private var result: ArrayList<Entry>? = null
        @Synchronized fun takePassword(): String? = password.also { password = null }
        @Synchronized fun offer(entries: ArrayList<Entry>): Boolean {
            if (cancelled) { destroy(entries); return false }
            result = entries; return true
        }
        @Synchronized fun take(): ArrayList<Entry>? = result.also { result = null }
        @Synchronized fun cancel() { cancelled = true; password = null; result?.let(::destroy); result = null }
    }
    var pending: Request? = null
        private set
    var restoredCount: Int? = null
        private set
    private var closed = false
    private var work: Work? = null

    fun open(source: File) {
        if (closed || state.isLocked || pending != null) return
        restoredCount = null; pending = Request(source, state.key); changed()
    }
    fun dismiss(request: Request) {
        if (pending !== request) return
        work?.cancel(); work = null
        request.workflow.cancel()
        request.entries?.let(::destroy); request.entries = null
        pending = null; changed()
    }
    fun dismissNotice() { restoredCount = null; changed() }
    fun close() {
        closed = true; work?.cancel(); work = null
        pending?.workflow?.cancel()
        pending?.entries?.let(::destroy); pending?.entries = null
        pending = null; restoredCount = null
    }
    fun submit(request: Request, password: String, active: () -> Boolean): Boolean = submitInternal(request, password, null, "encrypted", active)
    fun submitSelected(request: Request, password: String, format: String, variant: String, active: () -> Boolean): Boolean =
        submitInternal(request, password, format, variant, active)
    private fun submitInternal(request: Request, password: String, format: String?, variant: String, active: () -> Boolean): Boolean {
        if (!valid(request) || request.busy || request.count != null || !active()) return false
        if ((password.isEmpty() && !(format == "andotp" && variant == "plain")) || password.length > InputPolicy.MAX_TEXT_CHARS) {
            request.failed = true; changed(); return false
        }
        request.ticket = request.workflow.start(0, false)
        val ticket = request.ticket
        val task = Work(password)
        work = task; request.busy = true; request.failed = false; changed()
        try {
            worker.execute {
                if (state.isLocked) { task.cancel(); return@execute }
                val text = task.takePassword() ?: return@execute
                val entries = try { if (format == null) decode(request.source, text) else decodeSelected(request.source, text, format, variant) } catch (_: Exception) { null }
                if (entries != null && !task.offer(entries)) return@execute
                if (state.isLocked) { task.cancel(); return@execute }
                try { dispatch(Runnable {
                    if (!valid(request) || work !== task || !active()) { task.cancel(); return@Runnable }
                    work = null; request.busy = false
                    val decoded = task.take()
                    if (decoded != null && request.workflow.decoded(ticket, 0, active() && !state.isLocked,
                            decoded.size, decoded.toHashSet().size == decoded.size)) request.entries = decoded
                    else {
                        decoded?.let(::destroy)
                        request.workflow.readFailed(ticket, 0, active() && !state.isLocked)
                    }
                    request.failed = request.entries == null
                    changed()
                }) } catch (_: Exception) { task.cancel() }
            }
        } catch (_: Exception) {
            task.cancel(); work = null; request.busy = false; request.failed = true; changed()
        }
        return true
    }
    /** The final confirmation deliberately uses the current vault, never a stale pre-decode snapshot. */
    fun confirm(request: Request, replace: Boolean, active: () -> Boolean): Boolean {
        if (!valid(request) || !active()) {
            if (pending === request) dismiss(request)
            return false
        }
        if (request.busy) return false
        val entries = request.entries ?: return false
        if (!request.workflow.confirm(request.ticket, 0, active(), replace) ||
            !request.workflow.beginInstall(request.ticket, 0, active() && !state.isLocked, entries.size)) return false
        request.entries = null
        try {
            restoredCount = install(entries, request.workflow.replace)
            request.workflow.installed(request.ticket, true)
            pending = null
        } catch (_: Exception) { request.workflow.installed(request.ticket, false); request.failed = true }
        changed()
        return pending == null
    }
    private fun valid(request: Request) = !closed && !state.isLocked && pending === request && state.key === request.authorityKey

    companion object {
        private fun destroy(entries: ArrayList<Entry>) { entries.forEach { it.destroy() }; entries.clear() }
        private fun readSelectedBackup(source: File, password: String, format: String, variant: String): ArrayList<Entry> {
            val bytes = source.inputStream().use { BoundedInputReader.read(it, BoundedInputReader.MAX_DOCUMENT_BYTES) }
            val imported = try { VaultJson.stringToEntries(EncryptedBackupCodec.openSelected(password, bytes, format, variant)) }
                finally { bytes.fill(0) }
            try { require(imported.toHashSet().size == imported.size); return imported }
            catch (error: Exception) { destroy(imported); throw error }
        }
        private fun readBackup(source: File, password: String): ArrayList<Entry> {
            val bytes = source.inputStream().use { BoundedInputReader.read(it, BoundedInputReader.MAX_DOCUMENT_BYTES) }
            val imported = try { VaultJson.stringToEntries(EncryptedBackupCodec.open(password, bytes)) }
                finally { bytes.fill(0) }
            try {
                require(imported.isNotEmpty() && imported.toHashSet().size == imported.size)
                return imported
            } catch (error: Exception) { destroy(imported); throw error }
        }
    }
}
