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
        @Volatile var cancelled = false
            private set
        @Synchronized fun takePassword(): String? = password.also { password = null }
        @Synchronized fun cancel() { cancelled = true; password = null }
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
                if (attempt.cancelled) {
                    discard(result)
                } else {
                    try { dispatch(Runnable { complete(attempt, result) }) }
                    catch (_: Exception) { discard(result) }
                }
            }
        } catch (_: Exception) {
            attempt.cancel()
            complete(attempt, VaultController.Session.Error(VaultController.Failure.IO_FAILURE))
        }
        return true
    }

    private fun complete(attempt: Attempt, result: VaultController.Session) {
        if (closed || active !== attempt) { discard(result); return }
        active = null
        onBusy(false)
        try { onResult(result) } catch (error: Exception) { discard(result); throw error }
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
