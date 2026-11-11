package com.fairuniversal.offlineotp.desktop.control

import java.util.concurrent.Executor

/** UI-thread owner of one asynchronous unlock/create attempt. Worker results transfer ownership once. */
class DesktopUnlockOperation(
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val unlock: (String) -> VaultController.Session,
    private val onBusy: (Boolean) -> Unit,
    private val onResult: (VaultController.Session) -> Unit
) : AutoCloseable {
    private class Attempt(private var password: String?) {
        private var cancelled = false
        private var result: VaultController.Session? = null
        @Synchronized fun takePassword(): String? = password.also { password = null }
        @Synchronized fun offer(value: VaultController.Session): Boolean {
            if (cancelled) { discard(value); return false }
            result = value
            return true
        }
        @Synchronized fun takeResult(): VaultController.Session? = result.also { result = null }
        @Synchronized fun cancel() {
            cancelled = true; password = null
            result?.let(::discard); result = null
        }
    }
    private var active: Attempt? = null
    private var closed = false

    fun submit(password: String): Boolean {
        if (closed || active != null || password.isEmpty()) return false
        val attempt = Attempt(password)
        active = attempt
        onBusy(true)
        try {
            worker.execute {
                val text = attempt.takePassword() ?: return@execute
                val result = try { unlock(text) } catch (_: Exception) {
                    VaultController.Session.Error(VaultController.Failure.IO_FAILURE)
                }
                if (attempt.offer(result)) {
                    // The queued callback owns only the task, not the decrypted result. Closing
                    // can therefore wipe a loaded vault even if this callback is never delivered.
                    try { dispatch(Runnable { complete(attempt) }) }
                    catch (_: Exception) { attempt.cancel() }
                }
            }
        } catch (_: Exception) {
            attempt.takePassword()
            if (active === attempt && attempt.offer(VaultController.Session.Error(VaultController.Failure.IO_FAILURE))) {
                complete(attempt)
            }
        }
        return true
    }

    private fun complete(attempt: Attempt) {
        if (closed || active !== attempt) { attempt.cancel(); return }
        val result = attempt.takeResult() ?: return
        active = null
        try {
            onBusy(false)
            // Hosts may synchronously close during a busy-state update.
            if (closed) discard(result) else onResult(result)
        } catch (error: Exception) { discard(result); throw error }
    }

    /** Does not interrupt an in-flight atomic vault write; only invalidates result adoption. */
    override fun close() {
        if (closed) return
        closed = true
        active?.cancel()
        active = null
        onBusy(false)
    }

    companion object {
        /** Release keys and entries from an unlock whose screen/session no longer exists. */
        fun discard(result: VaultController.Session) {
            if (result is VaultController.Session.Loaded) {
                result.entries.forEach { it.destroy() }
                result.entries.clear()
                runCatching { result.key.destroy() }
            }
        }
    }
}
