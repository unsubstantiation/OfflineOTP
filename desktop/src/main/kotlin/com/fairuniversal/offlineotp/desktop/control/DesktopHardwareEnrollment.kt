package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.InputPolicy
import java.util.concurrent.Executor
import javax.crypto.SecretKey

/** Window-level, lock-first enrollment. Host blocks unlock/rekey until delivered completion. */
class DesktopHardwareEnrollment(private val state: DesktopVaultState, private val worker: Executor,
    private val dispatch: (Runnable) -> Unit, private val lockSession: () -> Unit,
    private val enroll: (CharArray?, SecretKey, HardwareKeyManager.CancelToken) -> HardwareKeyManager.Result<Unit>,
    private val changed: () -> Unit) : AutoCloseable {
    enum class Status { INPUT, WORKING, ENROLLED, FAILED }
    private class OwnedKey(private var bytes: ByteArray?) : SecretKey {
        override fun getAlgorithm() = "AES"
        override fun getFormat() = "RAW"
        @Synchronized override fun getEncoded(): ByteArray = checkNotNull(bytes).clone()
        @Synchronized override fun destroy() { bytes?.fill(0); bytes = null }
        @Synchronized override fun isDestroyed() = bytes == null
    }
    private class Work(private var key: OwnedKey?, private var pin: CharArray?) {
        val token = HardwareKeyManager.CancelToken()
        @Synchronized fun take(): Pair<OwnedKey, CharArray>? {
            val k = key ?: return null; val p = pin ?: return null
            key = null; pin = null; return k to p
        }
        @Synchronized fun cancel() { token.cancel(); key?.destroy(); key = null; pin?.fill(' '); pin = null }
    }
    var status = Status.INPUT
        private set
    private var work: Work? = null
    private var closed = false
    val busy get() = status == Status.WORKING
    fun submit(pin: CharArray, active: () -> Boolean): Boolean {
        if (closed || status != Status.INPUT || state.isLocked || pin.size > InputPolicy.MAX_TEXT_CHARS || !active() || closed || state.isLocked) {
            pin.fill(' '); return false
        }
        val key = try { OwnedKey(state.key.encoded ?: error("Unexportable key")) } catch (_: Exception) {
            pin.fill(' '); status = Status.FAILED; changed(); return false
        }
        val task = Work(key, pin); work = task; status = Status.WORKING
        try { lockSession() } catch (_: Exception) {
            task.cancel(); work = null; if (!closed) { status = Status.FAILED; changed() }; return false
        }
        if (closed || !state.isLocked) {
            task.cancel(); work = null; if (!closed) { status = Status.FAILED; changed() }; return false
        }
        changed()
        try {
            worker.execute {
                val input = task.take() ?: return@execute
                val result = try {
                    if (task.token.isCanceled()) HardwareKeyManager.Result.Canceled
                    else enroll(input.second.takeIf { it.isNotEmpty() }, input.first, task.token)
                } catch (_: Exception) { HardwareKeyManager.Result.Failed }
                finally { input.first.destroy(); input.second.fill(' ') }
                try { dispatch(Runnable {
                    if (!closed && work === task) {
                        work = null; status = if (result is HardwareKeyManager.Result.Ok) Status.ENROLLED else Status.FAILED; changed()
                    }
                }) } catch (_: Exception) { task.cancel() }
            }
        } catch (_: Exception) {
            task.cancel(); work = null; if (!closed) { status = Status.FAILED; changed() }
        }
        return true
    }
    override fun close() { closed = true; work?.cancel(); work = null }
}
