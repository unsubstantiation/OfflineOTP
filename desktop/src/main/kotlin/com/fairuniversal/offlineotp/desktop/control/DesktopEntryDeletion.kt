package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry

/**
 * UI-thread save-before-adopt deletion transaction. Not yet connected to the Compose dialog.
 * The host must prevent other mutations while busy and close this owner on lock/disposal.
 * Lock is mandatory on abandonment/expiry: the disk may already contain the candidate.
 */
class DesktopEntryDeletion(private val state: DesktopVaultState, private val save: DesktopDetachedSave,
    private val lockSession: () -> Unit) : AutoCloseable {
    enum class Result { DELETED, SAVE_FAILED }
    private var closed = false
    private var pending = false
    val busy get() = pending

    fun submit(target: Entry, active: () -> Boolean, completed: (Result) -> Unit): Boolean {
        if (closed || pending || state.isLocked) return false
        val before = state.snapshot()
        val index = before.indexOfFirst { it === target }
        if (index < 0) return false
        fun unchanged(): Boolean {
            val now = state.snapshot()
            return now.size == before.size && now.indices.all { now[it] === before[it] }
        }
        fun authorized(): Boolean {
            val live = try { active() } catch (_: Exception) { false }
            if (!live || state.isLocked || !unchanged()) {
                // Never leave a potentially stale live session available for a later save.
                if (pending && !state.isLocked) lockSession()
                return false
            }
            return !closed
        }
        pending = true
        val accepted = save.submitCandidate(state, ::authorized,
            prepare = { candidate -> candidate.removeAt(index).destroy() },
            completed = { saved ->
                if (!closed && pending) {
                    if (saved) {
                        if (!unchanged() || state.isLocked) {
                            lockSession()
                        } else {
                            // Remove exactly the original target only after successful persistence.
                            state.removeAt(index)?.destroy()
                            pending = false
                            completed(Result.DELETED)
                        }
                    } else {
                        pending = false
                        completed(Result.SAVE_FAILED)
                    }
                }
            })
        if (!accepted) pending = false
        return accepted
    }

    override fun close() {
        if (closed) return
        closed = true
        save.close()
        if (pending && !state.isLocked) lockSession()
        pending = false
    }
}
