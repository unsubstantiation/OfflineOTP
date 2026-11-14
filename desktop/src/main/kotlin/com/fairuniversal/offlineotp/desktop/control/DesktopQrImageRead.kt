package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.desktop.qr.ZxingQrDecoder
import java.io.File
import java.util.concurrent.Executor

/** Worker-only image reading, with secret result ownership until explicit UI consumption. */
class DesktopQrImageRead(
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val changed: () -> Unit,
    private val decode: (File) -> String? = ::readImage
) {
    private class Work {
        private var cancelled = false
        private var text: String? = null
        @Synchronized fun active() = !cancelled
        @Synchronized fun offer(value: String?): Boolean {
            if (cancelled) return false
            text = value; return true
        }
        @Synchronized fun take(): String? = text.also { text = null }
        @Synchronized fun cancel() { cancelled = true; text = null }
    }
    private var work: Work? = null
    private var text: String? = null
    private var closed = false
    var busy = false
        private set
    var failed = false
        private set
    val ready: Boolean get() = text != null

    fun clear() {
        work?.cancel(); work = null; text = null; busy = false; failed = false
        changed()
    }
    fun close() { closed = true; clear() }
    fun take(): String? = text.also { text = null; changed() }

    fun submit(file: File, active: () -> Boolean): Boolean {
        if (closed || busy || !active()) return false
        clear()
        val task = Work(); work = task; busy = true; changed()
        try {
            worker.execute {
                if (!task.active()) return@execute
                val decoded = try { decode(file)?.takeIf { it.isNotBlank() && it.length <= InputPolicy.MAX_URI_CHARS } }
                    catch (_: Exception) { null }
                if (!task.offer(decoded)) return@execute
                try { dispatch(Runnable {
                    if (closed || work !== task || !active()) { task.cancel(); return@Runnable }
                    work = null; busy = false; text = task.take(); failed = text == null; changed()
                }) } catch (_: Exception) { task.cancel() }
            }
        } catch (_: Exception) {
            task.cancel(); work = null; busy = false; failed = true; changed()
        }
        return true
    }
    companion object {
        private fun readImage(file: File): String? {
            val bytes = file.inputStream().use { BoundedInputReader.read(it, BoundedInputReader.MAX_DOCUMENT_BYTES) }
            return try { ZxingQrDecoder().decode(bytes) } finally { bytes.fill(0) }
        }
    }
}
