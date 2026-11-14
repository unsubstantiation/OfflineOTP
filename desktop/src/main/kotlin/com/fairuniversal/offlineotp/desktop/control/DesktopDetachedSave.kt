package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import java.util.concurrent.Executor
import javax.crypto.SecretKey

/**
 * UI-thread owner of a detached worker save. Not yet wired to production mutation paths.
 * The host MUST serialize all vault writes/rekeys, including across sessions, until the worker
 * finishes. Closing suppresses delivery; it cannot roll back or interrupt a started write.
 * Does not update live entries. A future transaction coordinator must handle adoption/rollback.
 */
class DesktopDetachedSave(private val worker: Executor, private val dispatch: (Runnable) -> Unit,
    private val writeGate: DesktopWriteGate = DesktopWriteGate(),
    private val persist: (SecretKey, ArrayList<Entry>) -> Boolean) : AutoCloseable {
    private class OwnedKey(private var bytes: ByteArray?) : SecretKey {
        override fun getAlgorithm() = "AES"
        override fun getFormat() = "RAW"
        @Synchronized override fun getEncoded() = checkNotNull(bytes).clone()
        @Synchronized override fun destroy() { bytes?.fill(0); bytes = null }
        @Synchronized override fun isDestroyed() = bytes == null
    }
    private class Snapshot(val key: OwnedKey, val entries: ArrayList<Entry>) {
        fun destroy() { entries.forEach { it.destroy() }; entries.clear(); key.destroy() }
    }
    private class Work(private var snapshot: Snapshot?, val lease: DesktopWriteGate.Lease) {
        @Synchronized fun take(): Snapshot? {
            if (!lease.start()) return null
            return snapshot.also { snapshot = null }
        }
        @Synchronized fun cancel() { snapshot?.destroy(); snapshot = null; lease.abandon() }
    }
    private var closed = false
    private var work: Work? = null
    /** Rejected UI delivery deliberately leaves this true: the host must fail closed. */
    val busy: Boolean get() = work != null

    fun submit(state: DesktopVaultState, active: () -> Boolean, completed: (Boolean) -> Unit): Boolean =
        submitCandidate(state, active, {}, completed)

    /** prepare may mutate only owned clones; removed clones must be destroyed by prepare. */
    fun submitCandidate(state: DesktopVaultState, active: () -> Boolean,
                        prepare: (ArrayList<Entry>) -> Unit, completed: (Boolean) -> Unit): Boolean {
        fun authorized() = try { active() } catch (_: Exception) { false }
        fun ready() = !closed && work == null && !state.isLocked
        if (!ready() || !authorized() || !ready()) return false
        val lease = writeGate.acquire() ?: return false
        val entries = ArrayList<Entry>()
        var key: OwnedKey? = null
        val snapshot = try {
            // Clones are created on the UI thread; live entries never cross into the worker.
            state.snapshot().forEach { entries.add(Entry(it.toJSON())) }
            prepare(entries)
            key = OwnedKey(state.key.encoded ?: error("Unexportable key"))
            Snapshot(key, entries)
        } catch (_: Exception) {
            entries.forEach { it.destroy() }; entries.clear(); key?.destroy(); lease.abandon()
            return false
        }
        if (!authorized() || !ready()) { snapshot.destroy(); lease.abandon(); return false }
        val task = Work(snapshot, lease); work = task
        try {
            worker.execute {
                val input = task.take() ?: return@execute
                val saved = try { persist(input.key, input.entries) } catch (_: Exception) { false }
                finally { try { input.destroy() } finally { lease.workerFinished() } }
                try {
                    dispatch(Runnable {
                        if (closed || work !== task) return@Runnable
                        val live = authorized()
                        if (closed || work !== task) return@Runnable
                        work = null
                        if (live && !state.isLocked) completed(saved)
                        lease.acknowledged()
                    })
                } catch (_: Exception) {
                    // No UI state access from worker. Inputs are already destroyed; fail closed.
                }
            }
        } catch (_: Exception) {
            task.cancel()
            if (work === task) work = null
            return false
        }
        return true
    }

    override fun close() {
        closed = true
        work?.cancel(); work = null
    }
}
