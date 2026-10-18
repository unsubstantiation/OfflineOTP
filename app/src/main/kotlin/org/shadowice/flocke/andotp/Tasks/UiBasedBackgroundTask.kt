package org.shadowice.flocke.andotp.Tasks

import android.os.Handler
import android.os.Looper
import android.util.Log

import org.shadowice.flocke.andotp.Utilities.VaultSession

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Android scheduling adapter over [TaskController] that delivers outcomes on the main thread. */
abstract class UiBasedBackgroundTask<Result>(private val failedResult: Result) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val callbackLock = Any()
    private val controller = TaskController(VaultSession.generation())
    private var callback: OutcomeCallback<Result>? = null
    private var pending: TaskOutcome<Result>? = null
    private var deliveryPosted = false
    private var future: Future<*>? = null
    private var cancellationCleaned = false
    private val sessionChanged = Runnable { if (isCanceled()) cancelFor(TaskCancellation.SESSION_REVOKED) }

    protected final fun taskGeneration(): Long {
        return controller.generation
    }

    fun getState(): TaskState {
        isCanceled()
        return controller.snapshot()
    }

    /** Legacy callback adapter. New clients must handle Failure/Cancelled explicitly. */
    fun setCallback(callback: UiCallback<Result>?) {
        if (callback == null) {
            setOutcomeCallback(null)
            return
        }
        setOutcomeCallback { outcome ->
            if (outcome is TaskOutcome.Value<*>) {
                @Suppress("UNCHECKED_CAST")
                callback.onResult(outcome.value as Result)
            } else if (outcome is TaskOutcome.Failure) {
                callback.onResult(failedResult)
            }
        }
    }

    fun setOutcomeCallback(callback: OutcomeCallback<Result>?) {
        synchronized(callbackLock) {
            if (controller.deliveryConsumed()) return
            this.callback = callback
            scheduleDelivery()
        }
    }

    private fun scheduleDelivery() {
        // Called under callbackLock. The queue captures this task, not a result or Activity.
        if (pending != null && callback != null && !deliveryPosted) {
            deliveryPosted = true
            mainThreadHandler.post { deliverPending() }
        }
    }

    private fun cancellation(): TaskOutcome.Cancelled<Result> {
        val reason = (controller.snapshot() as? TaskState.Cancelled)?.reason ?: TaskCancellation.SESSION_REVOKED
        return TaskOutcome.Cancelled(reason)
    }

    private fun deliverPending() {
        // Delivery and activation/revocation share the mutation lock. Never acquire it under callbackLock.
        synchronized(VaultSession.MutationLock) {
            var delivery: Pair<TaskOutcome<Result>, OutcomeCallback<Result>>? = null
            synchronized(callbackLock) {
                deliveryPosted = false
                val current = pending
                if (isCanceled() && current != null && current !is TaskOutcome.Cancelled) {
                    disposeOutcome(current)
                    pending = cancellation()
                }
                val deliverable = pending
                val bound = callback
                if (deliverable == null || bound == null) return
                pending = null
                if (!controller.claimDelivery()) {
                    disposeOutcome(deliverable)
                    callback = null
                    return
                }
                VaultSession.removeChangeListener(sessionChanged)
                callback = null
                delivery = Pair(deliverable, bound)
            }
            delivery?.let { (outcome, target) -> target.onOutcome(outcome) }
        }
    }

    fun execute() {
        synchronized(callbackLock) {
            isCanceled()
            when (controller.start()) {
                TaskStart.ALREADY_SUBMITTED -> throw IllegalStateException("Task already submitted")
                TaskStart.CANCELED -> {
                    executor.shutdownNow()
                    cleanupCancellation()
                    discardResult(failedResult)
                    pending = cancellation()
                    scheduleDelivery()
                    return
                }
                TaskStart.STARTED -> {}
            }
            VaultSession.addChangeListener(sessionChanged)
            future = executor.submit { runTask() }
        }
    }

    private fun runTask() {
        val outcome = try {
            if (isCanceled()) cancellation()
            else {
                val result = doInBackground()
                if (result == null) TaskOutcome.Failure<Result>(TaskFailure.NULL_RESULT)
                else TaskOutcome.Value(result)
            }
        } catch (error: Exception) {
            if (isCanceled()) cancellation()
            else {
                Log.e("UiBasedBackgroundTask", "Background operation failed")
                TaskOutcome.Failure(TaskFailure.UNEXPECTED_EXCEPTION)
            }
        } finally {
            executor.shutdown()
        }
        synchronized(callbackLock) {
            if (isCanceled()) {
                VaultSession.removeChangeListener(sessionChanged)
                disposeOutcome(outcome)
                // Cancellation may have already been delivered while work was still unwinding.
                pending = if (controller.deliveryConsumed()) null else cancellation()
            } else {
                val completion = if (outcome is TaskOutcome.Value) TaskCompletion.VALUE else TaskCompletion.FAILURE
                if (!controller.complete(completion)) {
                    disposeOutcome(outcome)
                    return
                }
                pending = outcome
            }
            scheduleDelivery()
        }
    }

    @Throws(Exception::class)
    protected abstract fun doInBackground(): Result

    /** Idempotent owned-input cleanup; no external I/O or MutationLock acquisition. */
    protected open fun onCancellation() {
    }

    private fun cleanupCancellation() {
        if (!cancellationCleaned) {
            cancellationCleaned = true
            onCancellation()
        }
    }

    protected open fun discardResult(result: Result) {
    }

    fun disposeOutcome(outcome: TaskOutcome<Result>) {
        if (outcome is TaskOutcome.Value<*>) {
            @Suppress("UNCHECKED_CAST")
            discardResult(outcome.value as Result)
        }
    }

    protected final fun wasCanceled(): Boolean {
        val state = controller.snapshot()
        return state is TaskState.Cancelled && state.reason == TaskCancellation.EXPLICIT
    }

    /** True if the task was canceled or the vault session generation advanced; overridden for broadcast backups. */
    open fun isCanceled(): Boolean {
        controller.observeGeneration(VaultSession.generation())
        return controller.snapshot() is TaskState.Cancelled
    }

    fun cancel() {
        cancelFor(TaskCancellation.EXPLICIT)
    }

    private fun cancelFor(reason: TaskCancellation) {
        synchronized(callbackLock) {
            controller.cancel(reason)
            if (controller.snapshot() !is TaskState.Cancelled) return
            VaultSession.removeChangeListener(sessionChanged)
            future?.cancel(true)
            executor.shutdownNow()
            cleanupCancellation()
            pending?.let { disposeOutcome(it) }
            pending = cancellation()
            scheduleDelivery()
        }
    }

    fun interface OutcomeCallback<Result> {
        fun onOutcome(outcome: TaskOutcome<Result>)
    }

    fun interface UiCallback<Result> {
        fun onResult(result: Result)
    }
}
