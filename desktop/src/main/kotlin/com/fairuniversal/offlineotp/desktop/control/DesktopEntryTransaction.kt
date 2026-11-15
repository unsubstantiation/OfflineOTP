package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import java.security.MessageDigest
import com.fairuniversal.offlineotp.Utilities.EntryUsage

/**
 * UI-thread save-before-adopt entry transaction used by Compose deletion/counter confirmation.
 * The host must prevent other mutations while busy and close this owner on lock/disposal.
 * Lock is mandatory on abandonment/expiry: the disk may already contain the candidate.
 */
class DesktopEntryTransaction(private val state: DesktopVaultState, private val save: DesktopDetachedSave,
    private val lockSession: () -> Unit) : AutoCloseable {
    enum class Kind { DELETE, ADVANCE_COUNTER }
    enum class Result { DELETED, COUNTER_ADVANCED, SAVE_FAILED }
    private class Baseline(val entries: ArrayList<Entry>) {
        private val digests = ArrayList<ByteArray>()
        init {
            try { entries.forEach { digests.add(fingerprint(it)) } }
            catch (error: Exception) { destroy(); throw error }
        }
        fun matches(now: ArrayList<Entry>): Boolean = try {
            now.size == entries.size && now.indices.all { index ->
                if (now[index] !== entries[index]) false else {
                    val current = fingerprint(now[index])
                    try { MessageDigest.isEqual(digests[index], current) } finally { current.fill(0) }
                }
            }
        } catch (_: Exception) { false }
        fun destroy() { digests.forEach { it.fill(0) }; digests.clear(); entries.clear() }
        companion object {
            private fun fingerprint(entry: Entry): ByteArray {
                val bytes = entry.toJSON().toString().toByteArray(Charsets.UTF_8)
                return try { MessageDigest.getInstance("SHA-256").digest(bytes) } finally { bytes.fill(0) }
            }
        }
    }
    private var baseline: Baseline? = null
    private fun clearBaseline() { baseline?.destroy(); baseline = null }
    private var closed = false
    private var pending = false
    val busy get() = pending

    fun submit(target: Entry, active: () -> Boolean, completed: (Result) -> Unit): Boolean =
        submit(target, Kind.DELETE, active, completed)

    fun submit(target: Entry, kind: Kind, active: () -> Boolean, completed: (Result) -> Unit): Boolean {
        if (closed || pending || state.isLocked) return false
        if (kind == Kind.ADVANCE_COUNTER && (!target.isCounterBased() || target.getCounter() < 0 || target.getCounter() >= EntryUsage.MAX_COUNTER)) return false
        val counter = target.getCounter()
        val before = state.snapshot()
        val index = before.indexOfFirst { it === target }
        if (index < 0) return false
        val captured = try { Baseline(before) } catch (_: Exception) { return false }
        baseline = captured
        fun unchanged(): Boolean = baseline === captured && captured.matches(state.snapshot())
        fun authorized(): Boolean {
            val live = try { active() } catch (_: Exception) { false }
            if (!live || state.isLocked || !unchanged()) {
                // Never leave a potentially stale live session available for a later save.
                try { if (pending && !state.isLocked) lockSession() } finally { clearBaseline() }
                return false
            }
            return !closed
        }
        pending = true
        val accepted = save.submitCandidate(state, ::authorized,
            prepare = { candidate ->
                when (kind) {
                    Kind.DELETE -> candidate.removeAt(index).destroy()
                    Kind.ADVANCE_COUNTER -> check(EntryUsage.advanceHotp(candidate[index]))
                }
            },
            completed = { saved ->
                if (!closed && pending) {
                    if (saved) {
                        if (!unchanged() || state.isLocked) {
                            try { lockSession() } finally { clearBaseline() }
                        } else {
                            val result = when (kind) {
                                Kind.DELETE -> { state.removeAt(index)?.destroy(); Result.DELETED }
                                Kind.ADVANCE_COUNTER -> {
                                    target.setCounter(counter + 1)
                                    target.updateOTP(true)
                                    Result.COUNTER_ADVANCED
                                }
                            }
                            pending = false
                            clearBaseline()
                            completed(result)
                        }
                    } else {
                        pending = false
                        clearBaseline()
                        completed(Result.SAVE_FAILED)
                    }
                }
            })
        if (!accepted) { pending = false; clearBaseline() }
        return accepted
    }

    override fun close() {
        if (closed) return
        closed = true
        save.close()
        clearBaseline()
        if (pending && !state.isLocked) lockSession()
        pending = false
    }
}
