package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.SessionOpenPgpSigningCredentials
import java.io.File
import java.util.concurrent.Executor

/** Reads selected key files off the UI thread. Queued private credentials are revocable. */
class DesktopAutomaticBackupSetup(private val worker: Executor, private val dispatch: (Runnable) -> Unit,
    private val changed: () -> Unit) {
    private class Input(val saved: List<ByteArray>, val files: List<File>, val signer: File?, val password: CharArray) : AutoCloseable {
        override fun close() { saved.forEach { it.fill(0) }; password.fill('\u0000') }
    }
    class Prepared internal constructor(val recipients: List<ByteArray>, val signing: SessionOpenPgpSigningCredentials.Snapshot?) : AutoCloseable {
        override fun close() { recipients.forEach { it.fill(0) }; signing?.close() }
        override fun toString() = "PreparedAutomaticBackup(redacted)"
    }
    private class Work(private var input: Input?) {
        private var cancelled = false
        private var result: Prepared? = null
        @Synchronized fun active() = !cancelled
        @Synchronized fun takeInput(): Input? = if (cancelled) null else input.also { input = null }
        @Synchronized fun offer(value: Prepared?): Boolean {
            if (cancelled) { value?.close(); return false }
            result = value; return true
        }
        @Synchronized fun takeResult(): Prepared? = result.also { result = null }
        @Synchronized fun cancel() { cancelled = true; input?.close(); input = null; result?.close(); result = null }
    }
    private var work: Work? = null
    private var closed = false
    var busy = false; private set
    var failed = false; private set
    fun cancel() { work?.cancel(); work = null; busy = false; failed = false; changed() }
    fun close() { closed = true; cancel() }

    /** Consumes password even when admission fails. Prepared data is borrowed only during apply. */
    fun submit(saved: List<ByteArray>, files: List<File>, signer: File?, password: CharArray,
               active: () -> Boolean, apply: (Prepared) -> Boolean): Boolean {
        try {
            if (closed || busy || !active()) return false
            cancel()
            val task = Work(Input(saved.map { it.copyOf() }, files.toList(), signer, password.copyOf()))
            work = task; busy = true; changed()
            try {
                worker.execute {
                    val input = task.takeInput() ?: return@execute
                    var recipients: List<ByteArray> = emptyList()
                    var signing: SessionOpenPgpSigningCredentials.Snapshot? = null
                    var prepared: Prepared? = null
                    try {
                        if (!task.active()) return@execute
                        recipients = DesktopAutomaticBackupRecipients.read(input.saved, input.files)
                        if (input.signer != null) {
                            if (!task.active()) return@execute
                            val secret = input.signer.inputStream().use { BoundedInputReader.read(it, BoundedInputReader.MAX_DOCUMENT_BYTES) }
                            val session = SessionOpenPgpSigningCredentials()
                            try {
                                check(session.load(secret, input.password))
                                signing = session.snapshot(session.revision()) ?: error("Signing input unavailable")
                            } finally { session.close() }
                        }
                        prepared = Prepared(recipients, signing); recipients = emptyList(); signing = null
                    } catch (_: Exception) { /* Never expose private filenames or crypto diagnostics. */ }
                    finally { input.close(); recipients.forEach { it.fill(0) }; signing?.close() }
                    if (!task.offer(prepared)) return@execute
                    try { dispatch(Runnable {
                        if (closed || work !== task || !active() || work !== task) {
                            task.cancel()
                            if (work === task) { work = null; busy = false; failed = false; changed() }
                            return@Runnable
                        }
                        val result = task.takeResult()
                        val accepted = try { result?.use(apply) ?: false } catch (_: Exception) { false }
                        if (work !== task || closed) return@Runnable
                        work = null; busy = false; failed = !accepted; changed()
                    }) } catch (_: Exception) { task.cancel() }
                }
            } catch (_: Exception) { task.cancel(); work = null; busy = false; failed = true; changed(); return false }
            return true
        } finally { password.fill('\u0000') }
    }
}
