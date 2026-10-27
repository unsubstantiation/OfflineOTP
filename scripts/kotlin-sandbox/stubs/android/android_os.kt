// Sandbox-only stand-ins, see android_content.kt.
package android.os

import java.util.Locale

/** Field/type-only stand-in for the platform window-token binder. */
interface IBinder

/**
 * `postDelayed` stays inert; `post` queues what the task runner delivers so the checks can drain
 * the "main thread" deterministically. No looper is created and no platform timing is exercised.
 */
open class Handler(private val looper: Looper?) {
    fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean = true
    fun removeCallbacks(runnable: Runnable) {}

    /** How often all pending callbacks were removed. */
    var removals = 0
        private set

    fun removeCallbacksAndMessages(token: Any?) {
        removals++
    }

    open fun post(runnable: Runnable): Boolean {
        posted.offer(runnable)
        return true
    }

    companion object {
        /** Deliveries waiting for the fake main loop; drained by Checks.kt. */
        @JvmField
        val posted: java.util.Queue<Runnable> = java.util.concurrent.ConcurrentLinkedQueue()
    }
}

class Looper private constructor() {
    companion object {
        @JvmStatic
        fun getMainLooper(): Looper = Looper()
    }
}

class SystemClock private constructor() {
    companion object {
        @JvmStatic
        fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L

        @JvmStatic
        fun uptimeMillis(): Long = System.nanoTime() / 1_000_000L

        /** Sleeps the calling thread; the platform does the same (no interruption state is kept). */
        @JvmStatic
        fun sleep(millis: Long) {
            Thread.sleep(millis)
        }
    }
}

class LocaleList {
    operator fun get(index: Int): Locale = Locale.ENGLISH
    fun size(): Int = 1
}

/**
 * Version-gate stand-in. `SDK_INT` is a runtime field (as on the platform, where it is not a
 * compile-time constant), so checks can drive a version branch and must restore it afterwards.
 */
object Build {
    object VERSION {
        @JvmField
        var SDK_INT: Int = 28
    }

    object VERSION_CODES {
        const val Q: Int = 29
        const val R: Int = 30
        const val TIRAMISU: Int = 33
    }
}

/**
 * Stand-in for the descriptor the storage helper opens documents through. `open`/`parseMode` are a
 * *replication* of the write-truncate subset only ("wt", the one mode the helper requests) over a
 * real file, so the checks can observe the bytes written and that the descriptor gets closed. Read
 * modes, pipes, sockets, provider-side semantics and the platform's error reporting are not
 * replicated. As on the platform, closing an [AutoCloseOutputStream] also closes its descriptor.
 */
class ParcelFileDescriptor private constructor(private val stream: java.io.FileOutputStream) : java.io.Closeable {
    fun getFileDescriptor(): java.io.FileDescriptor = stream.fd

    override fun close() = stream.close()

    class AutoCloseOutputStream(private val pfd: ParcelFileDescriptor) :
        java.io.FileOutputStream(pfd.getFileDescriptor()) {
        override fun close() {
            try {
                super.close()
            } finally {
                pfd.close()
            }
        }
    }

    /** Compilation-only input counterpart; shell output is not simulated by the sandbox. */
    class AutoCloseInputStream(private val pfd: ParcelFileDescriptor) : java.io.InputStream() {
        override fun read(): Int = -1
        override fun close() = pfd.close()
    }

    companion object {
        const val MODE_WRITE_ONLY = 0x20000000
        const val MODE_CREATE = 0x08000000
        const val MODE_TRUNCATE = 0x04000000

        @JvmStatic
        fun parseMode(mode: String?): Int =
            if (mode == "wt") MODE_WRITE_ONLY or MODE_CREATE or MODE_TRUNCATE
            else throw UnsupportedOperationException("sandbox stand-in only replicates mode \"wt\"")

        @JvmStatic
        fun open(file: java.io.File, mode: Int): ParcelFileDescriptor {
            if (mode != (MODE_WRITE_ONLY or MODE_CREATE or MODE_TRUNCATE))
                throw UnsupportedOperationException("sandbox stand-in only replicates write-truncate")
            return ParcelFileDescriptor(java.io.FileOutputStream(file))
        }
    }
}

/** The marker interface the platform's parcelable extras use. */
interface Parcelable
