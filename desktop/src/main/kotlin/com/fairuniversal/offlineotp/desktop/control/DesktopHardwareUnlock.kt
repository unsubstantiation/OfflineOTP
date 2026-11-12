package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.InputPolicy
import java.util.concurrent.Executor
import javax.crypto.SecretKey

/** Reuses the ordinary unlock result owner; only the hardware PIN/token handoff differs. */
class DesktopHardwareUnlock(worker: Executor, dispatch: (Runnable) -> Unit,
    private val recover: (CharArray?, HardwareKeyManager.CancelToken) -> HardwareKeyManager.Result<SecretKey>,
    private val open: (SecretKey) -> VaultController.Session,
    onBusy: (Boolean) -> Unit, onResult: (VaultController.Session) -> Unit) : AutoCloseable {
    private class Input(private var pin: CharArray?) {
        val token = HardwareKeyManager.CancelToken()
        @Synchronized fun take() = pin.also { pin = null }
        @Synchronized fun close() { token.cancel(); pin?.fill(' '); pin = null }
    }
    @Volatile private var input: Input? = null
    private var closed = false
    private val operation = DesktopUnlockOperation(worker, dispatch, {
        val task = input
        val pin = task?.take()
        if (task == null || pin == null) failure()
        else try {
            if (task.token.isCanceled()) failure()
            else when (val recovered = recover(pin.takeIf { it.isNotEmpty() }, task.token)) {
                is HardwareKeyManager.Result.Ok -> {
                    val key = recovered.value
                    var transferred = false
                    try {
                        if (task.token.isCanceled()) failure()
                        else open(key).also { transferred = it is VaultController.Session.Loaded && it.key === key }
                    } finally { if (!transferred) runCatching { key.destroy() } }
                }
                else -> failure()
            }
        } finally { pin.fill(' ') }
    }, { busy ->
        if (!busy) { input?.close(); input = null }
        onBusy(busy)
    }, onResult)

    fun submit(pin: CharArray): Boolean {
        if (closed || input != null || pin.size > InputPolicy.MAX_TEXT_CHARS) { pin.fill(' '); return false }
        val task = Input(pin); input = task
        if (!operation.submit("hardware-unlock")) { task.close(); input = null; return false }
        return true
    }
    override fun close() {
        closed = true; input?.close(); input = null; operation.close()
    }
    private fun failure() = VaultController.Session.Error(VaultController.Failure.IO_FAILURE)
}
