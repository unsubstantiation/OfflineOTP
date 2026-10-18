// Sandbox-only stand-ins, see ../../android/android_content.kt.
//
// They exist so the converted receivers and the WorkManager worker can be type-checked and their
// decision logic executed on a desktop JVM. They are NOT WorkManager: no scheduler, no database,
// no executor and no worker thread is involved, and the recorded enqueues are not work.
package androidx.work

import android.content.Context
import java.util.concurrent.TimeUnit

/** The unique work policies, like the platform's enum. */
enum class ExistingWorkPolicy { KEEP, REPLACE, APPEND, APPEND_OR_REPLACE }

/** Field/type-only stand-in for the WorkManager parameters. */
open class WorkerParameters

/** Stand-in for a one-time work request; it only remembers the worker class. */
class OneTimeWorkRequest private constructor(val workerClass: Class<*>) {
    class Builder(private val workerClass: Class<*>) {
        fun build(): OneTimeWorkRequest = OneTimeWorkRequest(workerClass)
    }
}

/**
 * Stand-in for the WorkManager worker base class.
 *
 * `stopped` is the platform's stop flag, which the framework sets when the work is cancelled or
 * the app is stopped; the checks flip it to drive the worker's early exits.
 */
abstract class ListenableWorker(context: Context, params: WorkerParameters) {
    private val appContext = context

    /** Whether the work was stopped. */
    var stopped: Boolean = false

    /** The application context, like the platform's `getApplicationContext()`. */
    fun getApplicationContext(): Context = appContext

    /** Whether the work was stopped, like the platform's `isStopped()`. */
    fun isStopped(): Boolean = stopped

    /** The worker result, like the platform's nested `ListenableWorker.Result`. */
    class Result internal constructor(internal val kind: String) {
        companion object {
            @JvmStatic fun success(): Result = Result("success")
            @JvmStatic fun failure(): Result = Result("failure")
            @JvmStatic fun retry(): Result = Result("retry")
        }
    }
}

/** Stand-in for the synchronous worker. */
abstract class Worker(context: Context, params: WorkerParameters) : ListenableWorker(context, params) {
    abstract fun doWork(): ListenableWorker.Result
}

/**
 * Stand-in for the WorkManager entry point.
 *
 * It records the queued and the cancelled unique work instead of scheduling it; the platform's
 * persistence, deduplication and execution are not modelled.
 */
class WorkManager private constructor() {
    /** The unique work requests that were enqueued, in call order. */
    val enqueued = ArrayList<Triple<String, ExistingWorkPolicy, OneTimeWorkRequest>>()

    /** The unique work names that were cancelled, in call order. */
    val cancelled = ArrayList<String>()

    fun enqueueUniqueWork(uniqueWorkName: String, existingWorkPolicy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        enqueued.add(Triple(uniqueWorkName, existingWorkPolicy, request))
    }

    fun cancelUniqueWork(uniqueWorkName: String) {
        cancelled.add(uniqueWorkName)
    }

    /** Test-only query/lifecycle shapes for the Kotlin IPC instrumentation source. */
    fun cancelAllWork(): Operation = Operation()
    fun pruneWork(): Operation = Operation()
    fun getWorkInfosForUniqueWork(uniqueWorkName: String): WorkFuture<List<WorkInfo>> = WorkFuture(emptyList())

    /** Discards the recorded work, so a check starts from a clean state. */
    fun reset() {
        enqueued.clear()
        cancelled.clear()
    }

    companion object {
        private val instance = WorkManager()

        @JvmStatic
        fun getInstance(context: Context): WorkManager = instance
    }
}
