package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.InputPolicy
import java.util.concurrent.Executor

/**
 * Window-level (not vault-composition-level) owner for a lock-first asynchronous rekey.
 * The host must disable the old session synchronously via lockSession and require fresh unlock
 * afterwards, on success OR failure. An authorized rekey is not interruptible once started.
 */
class DesktopPasswordChange(private val state: DesktopVaultState, private val worker: Executor,
    private val dispatch: (Runnable) -> Unit, private val lockSession: () -> Unit,
    private val change: (String, String) -> VaultController.Session,
    private val changed: () -> Unit) : AutoCloseable {
    enum class Status { INPUT, INVALID_INPUT, WORKING, CHANGED, FAILED }
    private class Credentials(var current: String?, var next: String?) {
        @Synchronized fun take(): Pair<String, String>? {
            val first = current ?: return null
            val second = next ?: return null
            current = null; next = null
            return first to second
        }
        @Synchronized fun clear() { current = null; next = null }
        override fun toString() = "RekeyCredentials(redacted)"
    }
    var status = Status.INPUT
        private set
    private var credentials: Credentials? = null
    private var closed = false
    private var attempted = false

    fun submit(current: String, next: String, confirmation: String, active: () -> Boolean): Boolean {
        if (closed || attempted || state.isLocked || !active() || closed || state.isLocked) return false
        if (current.isEmpty() || next.isEmpty() || next != confirmation || current == next ||
            current.length > InputPolicy.MAX_TEXT_CHARS || next.length > InputPolicy.MAX_TEXT_CHARS) {
            status = Status.INVALID_INPUT; changed(); return false
        }
        val task = Credentials(current, next)
        credentials = task; attempted = true
        // Never let another old-key save race a worker rekey, including during nested UI loops.
        try { lockSession() } catch (_: Exception) {
            task.clear(); credentials = null
            if (!closed) { status = Status.FAILED; changed() }
            return false
        }
        if (closed || !state.isLocked) {
            task.clear(); credentials = null
            if (!closed) { status = Status.FAILED; changed() }
            return false
        }
        status = Status.WORKING; changed()
        try {
            worker.execute {
                val input = task.take() ?: return@execute
                val result = try { change(input.first, input.second) } catch (_: Exception) { null }
                val outcome = if (result is VaultController.Session.Loaded) Status.CHANGED else Status.FAILED
                // No newly derived key/entries are ever queued for UI adoption.
                if (result != null) DesktopUnlockOperation.discard(result)
                try { dispatch(Runnable {
                    if (!closed && credentials === task) {
                        credentials = null; status = outcome; changed()
                    }
                }) } catch (_: Exception) { task.clear() }
            }
        } catch (_: Exception) {
            task.clear(); credentials = null
            if (!closed) { status = Status.FAILED; changed() }
        }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true; credentials?.clear(); credentials = null
    }
}
