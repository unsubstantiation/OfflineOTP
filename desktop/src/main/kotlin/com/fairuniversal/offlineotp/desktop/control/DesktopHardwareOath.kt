package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.HardwareOathClient
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.Utilities.HardwareOathRenamePolicy
import com.yubico.yubikit.oath.OathType
import java.util.concurrent.Executor

/** UI-thread OATH browser. No seed import, key enrollment or vault mutation. */
class DesktopHardwareOath(private val state: DesktopVaultState, private val worker: Executor,
    private val dispatch: (Runnable) -> Unit, private val changed: () -> Unit,
    private val list: (CharArray?, HardwareKeyManager.CancelToken) -> HardwareKeyManager.Result<HardwareOathClient.Accounts>,
    private val calculate: (CharArray?, String, HardwareOathClient.Account, HardwareKeyManager.CancelToken) -> HardwareKeyManager.Result<HardwareOathClient.Account>,
    private val delete: ((CharArray?, String, HardwareOathClient.Account, HardwareKeyManager.CancelToken) -> HardwareKeyManager.Result<Unit>)? = null,
    private val rename: ((CharArray?, String, HardwareOathClient.Account, String, String, HardwareKeyManager.CancelToken) -> HardwareKeyManager.Result<Unit>)? = null) {
    val canRename: Boolean get() = rename != null
    val canDelete: Boolean get() = delete != null
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
            Row(listOfNotNull(account.credential.issuer?.takeIf { it.isNotEmpty() }, account.credential.accountName).joinToString(" — ")
                    .map { if (it.isISOControl() || it == '\u2028' || it == '\u2029' || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }
                    .joinToString("").take(512),
                account.credential.oathType == OathType.HOTP,
                account.code.takeIf { request.shown == index && account.usableAt(now) })
        }.orEmpty()
    fun dismiss(request: Request) {
        if (pending !== request) return
        work?.cancel(); work = null; request.accounts = null; request.shown = null; pending = null; changed()
    }
    fun close() { closed = true; pending?.let(::dismiss) }
    /** Null index lists accounts; nonnull calculates only after explicit device-counter consent. */
    fun submit(request: Request, pin: CharArray, index: Int?, active: () -> Boolean): Boolean =
        submit(request, pin, index, false, active)

    fun submit(request: Request, pin: CharArray, index: Int?, deleteConfirmed: Boolean, active: () -> Boolean): Boolean =
        execute(request, pin, index, deleteConfirmed, null, active)

    /** The common form requires explicit confirmation and fresh password entry. */
    fun rename(request: Request, pin: CharArray, index: Int, issuer: String, name: String, active: () -> Boolean): Boolean {
        val account = request.accounts?.items?.getOrNull(index)
        if (rename == null || account == null || HardwareOathRenamePolicy.names(account.credential.oathType == OathType.HOTP,
                account.credential.period, issuer, name) == null) {
            pin.fill(' ')
            if (current(request) && !request.busy) { request.message = "Cannot rename: read the key again and use an unambiguous account/issuer fitting the 64-byte hardware ID limit, including its period prefix."; changed() }
            return false
        }
        return execute(request, pin, index, false, issuer to name, active)
    }

    private fun execute(request: Request, pin: CharArray, index: Int?, deleteConfirmed: Boolean,
                        renameTo: Pair<String, String>?, active: () -> Boolean): Boolean {
        val mutation = deleteConfirmed || renameTo != null
        if (!current(request) || request.busy || pin.size > InputPolicy.MAX_TEXT_CHARS || !active() || !current(request)) {
            pin.fill(' '); return false
        }
        if (deleteConfirmed && (index == null || delete == null)) { pin.fill(' '); return false }
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
                    else if (renameTo != null) when (val renamed = rename!!.invoke(password, prior!!.deviceId, account,
                        renameTo.first, renameTo.second, task.token)) {
                        is HardwareKeyManager.Result.Ok -> HardwareKeyManager.Result.Ok(HardwareOathClient.Accounts(prior.deviceId, emptyList()))
                        else -> renamed
                    }
                    else if (deleteConfirmed) when (val removed = delete!!.invoke(password, prior!!.deviceId, account, task.token)) {
                        is HardwareKeyManager.Result.Ok -> HardwareKeyManager.Result.Ok(HardwareOathClient.Accounts(prior.deviceId, emptyList()))
                        else -> removed
                    }
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
                                request.shown = if (mutation) null else index
                                request.message = if (renameTo != null) "Account renamed on the key. Vault unchanged. Read accounts again before another operation." else if (deleteConfirmed) "Account deleted from the key. Vault unchanged. Read accounts again before another operation." else if (request.accounts!!.items.isEmpty()) "No OATH accounts found." else null
                            }
                            else -> {
                                request.accounts = null
                                request.message = when (delivered) {
                                    HardwareKeyManager.Result.NoKey -> "No PC/SC security key found."
                                    HardwareKeyManager.Result.Canceled -> if (mutation) "Hardware operation canceled. The key may already have changed; read it before retrying. No automatic retry was made. Vault unchanged." else "Hardware operation canceled."
                                    else -> if (renameTo != null) "Rename did not report success. It may already have completed or the key may not support renaming. Read the key before retrying; no automatic retry was made. Vault unchanged." else if (deleteConfirmed) "Deletion did not report success. It may already have completed; read the key before retrying. No automatic retry was made. Vault unchanged."
                                        else "Could not access the key. Check its OATH password, connection and touch requirement."
                                }
                            }
                        }
                        changed()
                    } else {
                        task.cancel()
                        if (work === task) {
                            work = null; request.busy = false; request.accounts = null; request.shown = null
                            if (current(request)) { request.message = "Hardware result discarded because the session is inactive. Read the key again before another operation."; changed() }
                        }
                    }
                }) } catch (_: Exception) { task.cancel() }
            }
        } catch (_: Exception) { task.cancel(); work = null; request.busy = false; request.message = "Could not start hardware operation."; changed() }
        return true
    }
}
