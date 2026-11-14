package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.HardwareOathClient
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.yubico.yubikit.oath.OathType
import java.util.concurrent.Executor

/** UI-thread OATH browser. No seed import, key enrollment or vault mutation. */
class DesktopHardwareOath(private val state: DesktopVaultState, private val worker: Executor,
    private val dispatch: (Runnable) -> Unit, private val changed: () -> Unit,
    private val list: (CharArray?, HardwareKeyManager.CancelToken) -> HardwareKeyManager.Result<HardwareOathClient.Accounts>,
    private val calculate: (CharArray?, String, HardwareOathClient.Account, HardwareKeyManager.CancelToken) -> HardwareKeyManager.Result<HardwareOathClient.Account>) {
    class Request internal constructor() {
        var busy = false
            internal set
        var message: String? = null
            internal set
        internal var accounts: HardwareOathClient.Accounts? = null
        internal var shown: Int? = null
    }
    data class Row(val label: String, val counter: Boolean, val code: String?) {
        override fun toString() = "HardwareOathRow(redacted)"
    }
    private class Work(private var pin: CharArray?) {
        val token = HardwareKeyManager.CancelToken()
        private var result: HardwareKeyManager.Result<*>? = null
        @Synchronized fun offer(value: HardwareKeyManager.Result<*>): Boolean {
            if (token.isCanceled()) return false
            result = value; return true
        }
        @Synchronized fun takeResult() = result.also { result = null }
        @Synchronized fun take(): CharArray? = pin.also { pin = null }
        @Synchronized fun cancel() { token.cancel(); pin?.fill(' '); pin = null; result = null }
    }
    var pending: Request? = null
        private set
    private var work: Work? = null
    private var closed = false
    private fun current(request: Request) = !closed && !state.isLocked && pending === request
    fun open() { if (!closed && !state.isLocked && pending == null) { pending = Request(); changed() } }
    fun rows(request: Request, now: Long): List<Row> = if (!current(request)) emptyList() else
        request.accounts?.items?.mapIndexed { index, account ->
            Row(listOfNotNull(account.credential.issuer?.takeIf { it.isNotEmpty() }, account.credential.accountName).joinToString(" — "),
                account.credential.oathType == OathType.HOTP,
                account.code.takeIf { request.shown == index && account.usableAt(now) })
        }.orEmpty()
    fun dismiss(request: Request) {
        if (pending !== request) return
        work?.cancel(); work = null; request.accounts = null; request.shown = null; pending = null; changed()
    }
    fun close() { closed = true; pending?.let(::dismiss) }
    /** Null index lists accounts; nonnull calculates only after explicit device-counter consent. */
    fun submit(request: Request, pin: CharArray, index: Int?, active: () -> Boolean): Boolean {
        if (!current(request) || request.busy || pin.size > InputPolicy.MAX_TEXT_CHARS || !active() || !current(request)) {
            pin.fill(' '); return false
        }
        val prior = request.accounts
        val account = index?.let { prior?.items?.getOrNull(it) }
        if (index != null && account == null) { pin.fill(' '); return false }
        val task = Work(pin); work = task; request.busy = true; request.shown = null; request.message = null; changed()
        try {
            worker.execute {
                val password = task.take()
                if (task.token.isCanceled() || state.isLocked) { password?.fill(' '); return@execute }
                val result = try {
                    if (account == null) list(password, task.token)
                    else when (val calculated = calculate(password, prior!!.deviceId, account, task.token)) {
                        is HardwareKeyManager.Result.Ok -> HardwareKeyManager.Result.Ok(HardwareOathClient.Accounts(prior.deviceId,
                            prior.items.mapIndexed { i, old -> if (i == index) calculated.value else old }))
                        else -> calculated
                    }
                } catch (_: Exception) { HardwareKeyManager.Result.Failed }
                finally { password?.fill(' ') }
                if (!task.offer(result)) return@execute
                try { dispatch(Runnable {
                    if (current(request) && work === task && active() && current(request)) {
                        val delivered = task.takeResult() ?: return@Runnable
                        work = null; request.busy = false
                        when (delivered) {
                            is HardwareKeyManager.Result.Ok<*> -> {
                                request.accounts = delivered.value as HardwareOathClient.Accounts
                                request.shown = index
                                request.message = if (request.accounts!!.items.isEmpty()) "No OATH accounts found." else null
                            }
                            else -> {
                                request.accounts = null
                                request.message = when (delivered) {
                                    HardwareKeyManager.Result.NoKey -> "No PC/SC security key found."
                                    HardwareKeyManager.Result.Canceled -> "Hardware operation canceled."
                                    else -> "Could not access the key. Check its OATH password, connection and touch requirement."
                                }
                            }
                        }
                        changed()
                    } else task.cancel()
                }) } catch (_: Exception) { task.cancel() }
            }
        } catch (_: Exception) { task.cancel(); work = null; request.busy = false; request.message = "Could not start hardware operation."; changed() }
        return true
    }
}
